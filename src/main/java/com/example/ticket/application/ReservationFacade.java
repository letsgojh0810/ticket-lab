package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.PaymentService;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import com.example.ticket.infrastructure.redis.service.SeatCacheService; // [추가]
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import com.example.ticket.config.MetricsConfig;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final ReservationService reservationService;
    private final ReservationEventProducer eventProducer;
    private final SeatRepository seatRepository;
    private final PaymentService paymentService;
    private final WaitingQueueService waitingQueueService;
    private final SeatCacheService seatCacheService; // [변경] redisTemplate 대신 사용
    private final MetricsConfig metricsConfig;

    private static final String LOCK_KEY = "lock:seat:";

    public String reserve(Long seatId, Long userId) {
        Timer.Sample reservationSample = Timer.start();
        metricsConfig.incrementActiveReservations();

        // [STEP 1] Active User 확인 (대기열을 통과한 사용자만 예약 가능)
        if (!waitingQueueService.isAllowed(userId)) {
            metricsConfig.decrementActiveReservations();
            throw new IllegalStateException("대기열 진입이 필요합니다. /api/v1/queue/enter를 먼저 호출하세요.");
        }

        RLock lock = redissonClient.getLock(LOCK_KEY + seatId);

        try {
            // [STEP 2] 좌석 기본 정보 조회
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

            // [STEP 3] 분산 락 획득 (1초 대기, 2초 점유)
            Timer.Sample lockSample = Timer.start();
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                lockSample.stop(metricsConfig.getLockAcquisitionTimer());
                metricsConfig.getLockTimeoutCounter().increment();
                metricsConfig.getReservationFailedCounter().increment();
                metricsConfig.decrementActiveReservations();
                return "FAIL: 현재 접속자가 많아 처리에 실패했습니다. 잠시 후 다시 시도해 주세요.";
            }
            lockSample.stop(metricsConfig.getLockAcquisitionTimer());

            try {
                // [STEP 4] 이선좌(이미 선택된 좌석) 필터링 - 레디스 캐시 조회
                String currentStatus = seatCacheService.getSeatStatus(seatId);

                if (SeatStatus.SELECTED.name().equals(currentStatus)) {
                    return "FAIL: 현재 다른 사용자가 결제 진행 중입니다.";
                }
                if (SeatStatus.CONFIRMED.name().equals(currentStatus)) {
                    return "FAIL: 이미 판매가 완료된 좌석입니다.";
                }

                // [STEP 5] 레디스 임시 선점 (5분간 SELECTED 상태 유지)
                seatCacheService.updateSeatStatus(seatId, SeatStatus.SELECTED.name(), 5);

                // 🚩 선점 깃발을 꽂았으므로 락을 조기에 해제하여 다른 유저들이 대기하지 않게 합니다.
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

            // [STEP 6] 결제 시뮬레이션 (10초 대기)
            log.info("💳 유저 {} 가 좌석 {}번을 선점했습니다. 결제를 진행합니다.", userId, seatId);
            Timer.Sample paymentSample = Timer.start();
            Thread.sleep(10000);

            boolean isSuccess = paymentService.processPayment();
            paymentSample.stop(metricsConfig.getPaymentTimer());

            if (isSuccess) {
                // [STEP 7-A] 결제 성공: DB 예약 확정 및 캐시 상태 변경
                metricsConfig.getPaymentSuccessCounter().increment();
                Reservation reservation = reservationService.reserve(seatId, userId);
                seatCacheService.updateSeatStatus(seatId, SeatStatus.CONFIRMED.name(), 0); // 영구 확정

                // 이벤트 발행 및 대기열 권한 반납
                eventProducer.publish(ReservationEvent.success(reservation.getId(), userId, seatId, seat.getSeatNumber()));
                waitingQueueService.removeActiveUser(userId);

                metricsConfig.getReservationSuccessCounter().increment();
                reservationSample.stop(metricsConfig.getReservationTimer());
                metricsConfig.decrementActiveReservations();

                return "SUCCESS: 예약이 확정되었습니다!";
            } else {
                // [STEP 7-B] 결제 실패: 레디스 선점 데이터 삭제
                metricsConfig.getPaymentFailedCounter().increment();
                seatCacheService.deleteSeatStatus(seatId);
                eventProducer.publish(ReservationEvent.failed(userId, seatId, seat.getSeatNumber()));
                waitingQueueService.removeActiveUser(userId);

                metricsConfig.getReservationFailedCounter().increment();
                reservationSample.stop(metricsConfig.getReservationTimer());
                metricsConfig.decrementActiveReservations();

                return "FAIL: 결제가 실패하여 좌석 선점이 취소되었습니다.";
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsConfig.getReservationFailedCounter().increment();
            metricsConfig.decrementActiveReservations();
            return "ERROR: 시스템 오류가 발생했습니다.";
        } catch (Exception e) {
            log.error("예약 과정 중 에러 발생: ", e);
            seatCacheService.deleteSeatStatus(seatId);
            waitingQueueService.removeActiveUser(userId);
            metricsConfig.getReservationFailedCounter().increment();
            metricsConfig.decrementActiveReservations();
            return "ERROR: " + e.getMessage();
        }
    }
}