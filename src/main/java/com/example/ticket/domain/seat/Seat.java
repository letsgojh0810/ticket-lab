package com.example.ticket.domain.seat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor // JPA용 기본 생성자
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String seatNumber;

    private boolean isReserved;

    public Seat(String seatNumber) {
        this.seatNumber = seatNumber;
        this.isReserved = false;
    }

    public void reserve() {
        if (this.isReserved) {
            throw new IllegalStateException("이미 예약된 좌석입니다.");
        }
        this.isReserved = true;
    }

    public void cancel() {
        if (!this.isReserved) {
            throw new IllegalStateException("이미 취소되었습니다");
        }
        this.isReserved = false;
    }
}