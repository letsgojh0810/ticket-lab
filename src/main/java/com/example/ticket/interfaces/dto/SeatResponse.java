package com.example.ticket.interfaces.dto;

import com.example.ticket.domain.seat.Seat;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeatResponse {

    private Long seatId;
    private String seatNumber;
    private String status;  // AVAILABLE, RESERVED

    public static SeatResponse of(Seat seat, String redisStatus) {
        return SeatResponse.builder()
                .seatId(seat.getId())
                .seatNumber(seat.getSeatNumber())
                .status(redisStatus)
                .build();
    }
}
