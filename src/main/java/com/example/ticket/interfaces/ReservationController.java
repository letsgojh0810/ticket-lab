package com.example.ticket.interfaces;

import com.example.ticket.application.ReservationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationFacade reservationFacade;

    @PostMapping("/reserve/{seatId}")
    public String reserve(@PathVariable Long seatId, @RequestParam Long userId) {
        return reservationFacade.reserve(seatId, userId);
    }
}