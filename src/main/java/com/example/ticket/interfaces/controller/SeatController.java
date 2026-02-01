package com.example.ticket.interfaces.controller;

import com.example.ticket.application.SeatQueryService;
import com.example.ticket.interfaces.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 좌석 조회 API
 *
 * 좌석 선택 화면을 위한 조회 전용 컨트롤러입니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/seats")
public class SeatController {

    private final SeatQueryService seatQueryService;

    /**
     * 전체 좌석 목록 조회
     * GET /api/v1/seats
     *
     * @return 전체 좌석 목록 (상태 포함)
     */
    @GetMapping
    public ResponseEntity<List<SeatResponse>> getAllSeats() {
        List<SeatResponse> seats = seatQueryService.getAllSeats();
        return ResponseEntity.ok(seats);
    }

    /**
     * 단일 좌석 조회
     * GET /api/v1/seats/{seatId}
     *
     * @param seatId 좌석 ID
     * @return 좌석 정보 (상태 포함)
     */
    @GetMapping("/{seatId}")
    public ResponseEntity<SeatResponse> getSeat(@PathVariable Long seatId) {
        SeatResponse seat = seatQueryService.getSeat(seatId);
        return ResponseEntity.ok(seat);
    }

    /**
     * 예약 가능한 좌석만 조회
     * GET /api/v1/seats/available
     *
     * @return 예약 가능한 좌석 목록
     */
    @GetMapping("/available")
    public ResponseEntity<List<SeatResponse>> getAvailableSeats() {
        List<SeatResponse> seats = seatQueryService.getAvailableSeats();
        return ResponseEntity.ok(seats);
    }
}
