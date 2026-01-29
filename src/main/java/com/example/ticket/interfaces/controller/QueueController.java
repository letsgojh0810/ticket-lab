package com.example.ticket.interfaces.controller;

import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import com.example.ticket.interfaces.dto.QueueEnterResponse;
import com.example.ticket.interfaces.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/queue")
public class QueueController {

    private final WaitingQueueService waitingQueueService;

    /**
     * 대기열 진입
     * POST /api/v1/queue/enter?userId=100
     */
    @PostMapping("/enter")
    public ResponseEntity<QueueEnterResponse> enterQueue(@RequestParam Long userId) {
        // 이미 Active User면 바로 READY
        if (waitingQueueService.isAllowed(userId)) {
            return ResponseEntity.ok(QueueEnterResponse.builder()
                    .userId(userId)
                    .status("READY")
                    .message("입장 완료! 예약을 진행하세요.")
                    .build());
        }

        // 대기열 등록
        Long rank = waitingQueueService.registerAndGetRank(userId);

        return ResponseEntity.ok(QueueEnterResponse.builder()
                .userId(userId)
                .status("WAITING")
                .rank(rank)
                .message(String.format("현재 대기 중입니다. 순번: %d번", rank))
                .build());
    }

    /**
     * 대기열 상태 확인
     * GET /api/v1/queue/status?userId=100
     */
    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getQueueStatus(@RequestParam Long userId) {
        // Active User 확인
        if (waitingQueueService.isAllowed(userId)) {
            return ResponseEntity.ok(QueueStatusResponse.builder()
                    .userId(userId)
                    .status("READY")
                    .message("입장 완료! 예약이 가능합니다.")
                    .activeUserCount(waitingQueueService.getActiveUserCount())
                    .build());
        }

        // 대기열 순번 확인
        Long rank = waitingQueueService.registerAndGetRank(userId); // 없으면 등록도 함

        return ResponseEntity.ok(QueueStatusResponse.builder()
                .userId(userId)
                .status("WAITING")
                .rank(rank)
                .message(String.format("현재 대기 중입니다. 순번: %d번", rank))
                .activeUserCount(waitingQueueService.getActiveUserCount())
                .build());
    }

    /**
     * 대기열 이탈 (취소)
     * DELETE /api/v1/queue?userId=100
     */
    @DeleteMapping
    public ResponseEntity<String> leaveQueue(@RequestParam Long userId) {
        waitingQueueService.removeActiveUser(userId);
        return ResponseEntity.ok("대기열에서 나갔습니다.");
    }
}
