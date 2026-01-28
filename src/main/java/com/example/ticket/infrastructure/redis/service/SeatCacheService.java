package com.example.ticket.infrastructure.redis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String STATE_KEY = "state:seat:";

    // 1. 좌석 상태 조회
    public String getSeatStatus(Long seatId) {
        String status = redisTemplate.opsForValue().get(STATE_KEY + seatId);
        // 값이 없으면 DB 조회를 최소화하기 위해 "AVAILABLE"로 간주하거나 별도 처리
        return (status != null) ? status : "AVAILABLE";
    }

    // 2. 좌석 상태 업데이트 (선점 시 5분 TTL 등)
    public void updateSeatStatus(Long seatId, String status, long timeoutMinutes) {
        if (timeoutMinutes > 0) {
            redisTemplate.opsForValue().set(STATE_KEY + seatId, status, timeoutMinutes, TimeUnit.MINUTES);
        } else {
            redisTemplate.opsForValue().set(STATE_KEY + seatId, status);
        }
    }

    // 3. 좌석 상태 삭제 (결제 취소 시 복구용)
    public void deleteSeatStatus(Long seatId) {
        redisTemplate.delete(STATE_KEY + seatId);
    }
}