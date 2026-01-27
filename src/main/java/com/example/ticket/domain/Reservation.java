package com.example.ticket.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long seatId;

    private LocalDateTime reservedAt;

    public Reservation(Long userId, Long seatId) {
        this.userId = userId;
        this.seatId = seatId;
        this.reservedAt = LocalDateTime.now();
    }
}