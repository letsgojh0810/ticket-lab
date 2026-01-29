package com.example.ticket.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatusResponse {
    private Long userId;
    private String status;          // "WAITING" or "READY"
    private Long rank;              // 대기 순번 (WAITING일 때만)
    private String message;
    private Long activeUserCount;   // 현재 활성 유저 수 (모니터링용)
}
