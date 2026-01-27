package com.example.ticket.domain.seat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    private boolean isReserved;

    public void reserve() {
        if (this.isReserved) {
            throw new IllegalStateException("이미 선점된 좌석입니다.");
        }
        this.isReserved = true;
    }
}