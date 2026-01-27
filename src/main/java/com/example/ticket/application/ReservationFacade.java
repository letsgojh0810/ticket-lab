package com.example.ticket.application;

import com.example.ticket.domain.reservation.ReservationService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReservationService reservationService;

    private static final String LOCK_KEY = "lock:seat:";
    private static final String STATE_KEY = "state:seat:";

    public String reserve(Long seatId, Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY + seatId);
        String seatStateKey = STATE_KEY + seatId;

        try {
            // 1. 락 획득 시도 (1초 대기, 2초 점유)
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                return "FAIL: 현재 요청이 많아 처리가 지연되고 있습니다.";
            }

            // 2. Redis 선점 상태 확인 (이선좌 1차 필터링)
            String currentStatus = redisTemplate.opsForValue().get(seatStateKey);
            if ("RESERVED".equals(currentStatus)) {
                return "FAIL: 이미 선택된 좌석입니다.";
            }

            // 3. 실제 DB 예약 진행
            reservationService.reserve(seatId, userId);

            // 4. 성공 시 Redis에 선점 상태 저장 (TTL 5분 설정)
            redisTemplate.opsForValue().set(seatStateKey, "RESERVED", 5, TimeUnit.MINUTES);

            return "SUCCESS";

        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}