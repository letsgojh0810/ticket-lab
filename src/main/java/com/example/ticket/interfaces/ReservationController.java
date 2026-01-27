package com.example.ticket.interfaces;

import com.example.ticket.application.ReservationFacade;
import com.example.ticket.domain.reservation.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationFacade reservationFacade;
    private final ReservationService reservationService;

    @PostMapping("/reserve/{seatId}")
    public String reserve(@PathVariable Long seatId, @RequestParam Long userId) {
        return reservationFacade.reserve(seatId, userId);
    }

    @PostMapping("/cancel/{seatId}")
    public String cancel(@PathVariable Long seatId, @RequestParam Long userId) {
        return reservationService.cancel(seatId, userId);
    }
}