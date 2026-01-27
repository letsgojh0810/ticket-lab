package com.example.ticket.domain.reservation;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationRepository;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReservationEventProducer reservationEventProducer;

    @Transactional
    public Reservation reserve(Long seatId, Long userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

        // 엔티티 내부 로직으로 좌석 예약 상태 변경
        seat.reserve();

        // 예약 기록 저장
        Reservation reservation = new Reservation(userId, seatId);
        return reservationRepository.save(reservation);
    }

    @Transactional
    public String cancel(Long seatId, Long userId) {
        try {
            // 1. 좌석 조회
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

            // 2. 취소 실행 (여기서 IllegalStateException이 발생할 수 있음)
            seat.cancel();

            // 3. Redis 삭제 및 Kafka 발행... (기존 로직)
            redisTemplate.delete("state:seat:" + seatId);
            reservationEventProducer.publish(ReservationEvent.cancelled(userId, seatId, seat.getSeatNumber()));

            return "SUCCESS: 취소가 완료되었습니다.";

        } catch (IllegalStateException e) {
            return "FAIL: " + e.getMessage();
        } catch (Exception e) {
            return "FAIL: 알 수 없는 에러가 발생했습니다.";
        }
    }
}