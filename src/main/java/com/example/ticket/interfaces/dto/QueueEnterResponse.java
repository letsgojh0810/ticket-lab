package com.example.ticket.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueEnterResponse {
    private Long userId;
    private String status;  // "WAITING" or "READY"
    private Long rank;      // 대기 순번 (WAITING일 때만)
    private String message;
}
