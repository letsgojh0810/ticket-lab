package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.PaymentService;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
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
    private final PaymentService paymentService; // 50% 확률 결제 서비스 추가

    private static final String LOCK_KEY = "lock:seat:";
    private static final String STATE_KEY = "state:seat:";

    public String reserve(Long seatId, Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY + seatId);
        String seatStateKey = STATE_KEY + seatId;

        // 1. 좌석 정보 미리 조회
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new IllegalArgumentException("좌석 없음"));

        try {
            // 락 획득 시도
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                return "FAIL: 현재 요청이 많습니다.";
            }

            // 이선좌 필터링
            // SELECTED(결제중) 거나 CONFIRMED(예약완료)면 튕겨냄
            String currentStatus = redisTemplate.opsForValue().get(seatStateKey);

            if (currentStatus != null) {
                if (SeatStatus.SELECTED.name().equals(currentStatus)) {
                    // 누군가 결제 중이니 1~2분 뒤에 다시 오라고 유도
                    return "FAIL: 현재 다른 사용자가 결제 진행 중입니다. 잠시 후 다시 시도해 주세요.";
                }
                if (SeatStatus.CONFIRMED.name().equals(currentStatus)) {
                    // 이미 팔렸으니 다른 좌석을 찾으라고 안내
                    return "FAIL: 이미 판매가 완료된 좌석입니다. 다른 좌석을 선택해 주세요.";
                }
            }

            // Redis 임시 선점
            // 아직 DB는 안 건드림! 레디스에만 "5분간 내가 찜함"이라고 표시
            redisTemplate.opsForValue().set(seatStateKey, "SELECTED", 5, TimeUnit.MINUTES);

            // 락 해제: 선점 깃발을 꽂았으니 이제 다른 사람들은 레디스 선에서 컷당함.
            // 따라서 결제하는 동안 락을 길게 잡을 필요가 없음 (성능 최적화)
            lock.unlock();

            // [STEP 4] 결제 시뮬레이션 (50% 확률)
            System.out.println("💳 유저 " + userId + " 결제 진행 중 (50% 확률)...");
            boolean isSuccess = paymentService.processPayment();

            if (isSuccess) {
                // [STEP 5-A] 결제 성공: 진짜 DB에 예약 확정!
                Reservation reservation = reservationService.reserve(seatId, userId);

                // 레디스 상태를 확정(CONFIRMED)으로 변경
                redisTemplate.opsForValue().set(seatStateKey, "CONFIRMED");

                // 카프카 성공 이벤트 발행
                eventProducer.publish(ReservationEvent.success(reservation.getId(), userId, seatId, seat.getSeatNumber()));
                return "SUCCESS: 예약이 확정되었습니다!";
            } else {
                // [STEP 5-B] 결제 실패: 레디스 선점 데이터 삭제 (복구)
                redisTemplate.delete(seatStateKey);

                // 카프카 실패 이벤트 발행
                eventProducer.publish(ReservationEvent.failed(userId, seatId, seat.getSeatNumber()));
                return "FAIL: 결제 실패! 좌석이 다시 풀렸습니다.";
            }

        } catch (Exception e) {
            // 에러 발생 시 레디스 데이터 삭제 (안전장치)
            redisTemplate.delete(seatStateKey);
            return "ERROR: " + e.getMessage();
        } finally {
            // 혹시라도 락이 안 풀렸다면 해제
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}