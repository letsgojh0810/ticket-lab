package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReservationService reservationService;
    private final ReservationEventProducer eventProducer;
    private final SeatRepository seatRepository;

    private static final String LOCK_KEY = "lock:seat:";
    private static final String STATE_KEY = "state:seat:";

    public String reserve(Long seatId, Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY + seatId);
        String seatStateKey = STATE_KEY + seatId;
        Seat seat = null;

        try {
            // 1. 락 획득 시도 (1초 대기, 2초 점유)
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                // 락 획득 실패 이벤트 발행
                seat = seatRepository.findById(seatId).orElse(null);
                if (seat != null) {
                    eventProducer.publish(ReservationEvent.failed(userId, seatId, seat.getSeatNumber()));
                }
                return "FAIL: 현재 요청이 많아 처리가 지연되고 있습니다.";
            }

            // 2. Redis 선점 상태 확인 (이선좌 1차 필터링)
            String currentStatus = redisTemplate.opsForValue().get(seatStateKey);
            if ("RESERVED".equals(currentStatus)) {
                seat = seatRepository.findById(seatId).orElse(null);
                if (seat != null) {
                    eventProducer.publish(ReservationEvent.failed(userId, seatId, seat.getSeatNumber()));
                }
                return "FAIL: 이미 선택된 좌석입니다.";
            }

            // 3. 실제 DB 예약 진행
            Reservation reservation = reservationService.reserve(seatId, userId);
            seat = seatRepository.findById(seatId).orElseThrow();

            // 4. 성공 시 Redis에 선점 상태 저장 (TTL 5분 설정)
            redisTemplate.opsForValue().set(seatStateKey, "RESERVED", 5, TimeUnit.MINUTES);

            // 5. 예약 성공 이벤트 발행 (Kafka)
            eventProducer.publish(ReservationEvent.success(
                    reservation.getId(),
                    userId,
                    seatId,
                    seat.getSeatNumber()
            ));

            return "SUCCESS";

        } catch (Exception e) {
            // 예외 발생 시 실패 이벤트 발행
            if (seat == null) {
                seat = seatRepository.findById(seatId).orElse(null);
            }
            if (seat != null) {
                eventProducer.publish(ReservationEvent.failed(userId, seatId, seat.getSeatNumber()));
            }
            return "ERROR: " + e.getMessage();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}