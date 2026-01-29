package com.example.ticket.interfaces.controller;

import com.example.ticket.application.ReservationFacade;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.interfaces.dto.ReservationRequest;
import com.example.ticket.interfaces.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reservations") // 버전 관리를 위해 경로 추가
public class ReservationController {

    private final ReservationFacade reservationFacade;
    private final ReservationService reservationService;

    /**
     * 예약하기 (Active User만 호출 가능)
     * POST /api/v1/reservations/reserve
     *
     * 사전 조건: /api/v1/queue/enter를 통해 대기열 진입 필수
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@RequestBody ReservationRequest request) {
        try {
            String result = reservationFacade.reserve(request.getSeatId(), request.getUserId());

            // 결과 문자열에 따라 적절한 DTO 응답 생성
            if (result.startsWith("SUCCESS")) {
                return ResponseEntity.ok(ReservationResponse.ok(result));
            } else {
                // 실패 시 409 Conflict
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ReservationResponse.fail(result));
            }
        } catch (IllegalStateException e) {
            // 대기열 미진입 시 403 Forbidden
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ReservationResponse.fail(e.getMessage()));
        }
    }

    // 2. 취소하기
    @PostMapping("/cancel")
    public ResponseEntity<ReservationResponse> cancel(@RequestBody ReservationRequest request) {
        String result = reservationService.cancel(request.getSeatId(), request.getUserId());
        return ResponseEntity.ok(ReservationResponse.ok(result));
    }
}