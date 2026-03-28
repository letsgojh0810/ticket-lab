package com.example.ticket.domain.reservation;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReservationEventProducer reservationEventProducer;

    /**
     * 좌석 선점: Seat 상태를 SELECTED로 변경 후 Reservation을 HELD 상태로 저장
     */
    @Transactional
    public Reservation hold(Long seatId, Long userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));
        seat.select();

        Reservation reservation = new Reservation(userId, seatId);
        return reservationRepository.save(reservation);
    }

    /**
     * 예약 확정: HELD → CONFIRMED, Seat SELECTED → CONFIRMED
     */
    @Transactional
    public Reservation confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 예약입니다."));
        reservation.confirm();

        Seat seat = seatRepository.findById(reservation.getSeatId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));
        seat.confirm();

        return reservationRepository.save(reservation);
    }

    /**
     * 예약 조회
     */
    public Reservation findById(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 예약입니다."));
    }

    /**
     * 결제 실패 등으로 인한 좌석 해제: Seat 상태를 AVAILABLE로 복원
     */
    @Transactional
    public void releaseSeat(Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));
        seat.release();
    }

    /**
     * 취소: 좌석 상태 복원 + Reservation CANCELLED + Redis 삭제 + Kafka 발행
     */
    @Transactional
    public String cancel(Long seatId, Long userId) {
        try {
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

            seat.release();

            // Reservation 상태를 CANCELLED로 업데이트 (존재하는 경우)
            reservationRepository.findBySeatIdAndUserId(seatId, userId).ifPresent(reservation -> {
                reservation.cancel();
                reservationRepository.save(reservation);
            });

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
