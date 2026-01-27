package com.example.ticket.domain.seat;

public enum SeatStatus {
    AVAILABLE,  // 선택 가능
    SELECTED,   // 선점됨 (결제 중)
    CONFIRMED   // 예약 확정
}