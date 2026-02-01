package com.example.ticket.application;

import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.example.ticket.interfaces.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 좌석 조회 서비스
 *
 * 좌석 목록 화면을 위한 조회 전용 서비스입니다.
 * DB에서 좌석 메타 정보를, Redis에서 실시간 상태를 조합합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatQueryService {

    private final SeatRepository seatRepository;
    private final SeatCacheService seatCacheService;

    /**
     * 전체 좌석 목록 조회
     *
     * 1. DB에서 좌석 메타 정보 조회 (seatId, seatNumber 등)
     * 2. Redis에서 각 좌석의 실시간 상태 조회
     * 3. 조합하여 반환
     *
     * @return 좌석 목록 (상태 포함)
     */
    public List<SeatResponse> getAllSeats() {
        List<Seat> seats = seatRepository.findAll();

        return seats.stream()
                .map(seat -> {
                    String status = seatCacheService.getSeatStatus(seat.getId());
                    return SeatResponse.of(seat, status);
                })
                .toList();
    }

    /**
     * 단일 좌석 조회
     *
     * @param seatId 좌석 ID
     * @return 좌석 정보 (상태 포함)
     */
    public SeatResponse getSeat(Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다: " + seatId));

        String status = seatCacheService.getSeatStatus(seatId);
        return SeatResponse.of(seat, status);
    }

    /**
     * 예약 가능한 좌석만 조회
     *
     * @return 예약 가능한 좌석 목록
     */
    public List<SeatResponse> getAvailableSeats() {
        return getAllSeats().stream()
                .filter(seat -> "AVAILABLE".equals(seat.getStatus()))
                .toList();
    }
}
