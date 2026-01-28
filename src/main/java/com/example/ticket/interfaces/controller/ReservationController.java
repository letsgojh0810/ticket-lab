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

    // 1. 예약하기: @RequestBody를 통해 DTO로 데이터를 받습니다.
    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@RequestBody ReservationRequest request) {
        String result = reservationFacade.reserve(request.getSeatId(), request.getUserId());

        // 결과 문자열에 따라 적절한 DTO 응답 생성
        if (result.startsWith("SUCCESS")) {
            return ResponseEntity.ok(ReservationResponse.ok(result));
        } else if (result.contains("대기")) {
            // 현재 순번 정보를 포함해 응답 (Facade 로직 개선에 따라 Rank 추출 가능)
            return ResponseEntity.ok(ReservationResponse.waiting(null));
        } else {
            // 실패 시 409 Conflict 또는 400 Bad Request
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ReservationResponse.fail(result));
        }
    }

    // 2. 취소하기
    @PostMapping("/cancel")
    public ResponseEntity<ReservationResponse> cancel(@RequestBody ReservationRequest request) {
        String result = reservationService.cancel(request.getSeatId(), request.getUserId());
        return ResponseEntity.ok(ReservationResponse.ok(result));
    }
}