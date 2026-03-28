package com.example.ticket.domain.seat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String seatNumber;

    @Enumerated(EnumType.STRING)
    private SeatStatus status = SeatStatus.AVAILABLE;

    public Seat(String seatNumber) {
        this.seatNumber = seatNumber;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * 좌석 선점: AVAILABLE → SELECTED
     */
    public void select() {
        if (this.status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("선점할 수 없는 좌석입니다. 현재 상태: " + this.status);
        }
        this.status = SeatStatus.SELECTED;
    }

    /**
     * 예약 확정: SELECTED → CONFIRMED
     */
    public void confirm() {
        if (this.status != SeatStatus.SELECTED) {
            throw new IllegalStateException("확정할 수 없는 좌석입니다. 현재 상태: " + this.status);
        }
        this.status = SeatStatus.CONFIRMED;
    }

    /**
     * 좌석 해제: 어떤 상태에서든 AVAILABLE로 복원
     */
    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }
}
