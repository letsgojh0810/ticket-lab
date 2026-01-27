package com.example.ticket.domain.reservation;

import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationRepository;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    @Transactional
    public void reserve(Long seatId, Long userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

        // 엔티티 내부 로직으로 좌석 예약 상태 변경
        seat.reserve();

        // 예약 기록 저장
        Reservation reservation = new Reservation(userId, seatId);
        reservationRepository.save(reservation);
    }
}