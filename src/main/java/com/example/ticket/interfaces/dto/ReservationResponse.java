package com.example.ticket.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ReservationResponse {
    private boolean success;
    private String message;
    private Long rank; // 대기 순번 (대기 중일 때만 포함)

    // 성공 응답 정적 팩토리 메서드
    public static ReservationResponse ok(String message) {
        return new ReservationResponse(true, message, null);
    }

    // 대기 응답 정적 팩토리 메서드
    public static ReservationResponse waiting(Long rank) {
        return new ReservationResponse(false, "현재 대기 중입니다.", rank);
    }

    // 실패 응답 정적 팩토리 메서드
    public static ReservationResponse fail(String message) {
        return new ReservationResponse(false, message, null);
    }
}
