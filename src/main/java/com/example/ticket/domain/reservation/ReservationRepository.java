package com.example.ticket.domain.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    void deleteBySeatIdAndUserId(Long seatId, Long userId);
}