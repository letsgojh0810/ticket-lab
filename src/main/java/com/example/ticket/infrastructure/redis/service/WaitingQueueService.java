package com.example.ticket.infrastructure.redis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {
    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final String ACTIVE_KEY_PREFIX = "ticket:active:users:";
    private static final int ACTIVE_USER_TTL_MINUTES = 5; // 5분 TTL

    // 대기열 등록 및 순번 확인
    public Long registerAndGetRank(Long userId) {
        String userIdStr = userId.toString();
        redisTemplate.opsForZSet().add(QUEUE_KEY, userIdStr, (double) System.currentTimeMillis());
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, userIdStr);
        return (rank != null) ? rank + 1 : -1L;
    }

    // 입장 허가 여부 확인 (개별 키 존재 여부)
    public boolean isAllowed(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // Active User로 등록 (TTL 5분)
    public void addActiveUser(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "activated", ACTIVE_USER_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("✅ Active User 추가: userId={}, TTL={}분", userId, ACTIVE_USER_TTL_MINUTES);
    }

    // 입장 권한 반납 (결제 완료/실패 시 호출)
    public void removeActiveUser(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        redisTemplate.delete(key);
        // 대기열에서도 제거 (중복 방지)
        redisTemplate.opsForZSet().remove(QUEUE_KEY, userId.toString());
        log.debug("🔴 Active User 제거: userId={}", userId);
    }

    // 현재 Active User 수 조회 (모니터링용)
    public Long getActiveUserCount() {
        Set<String> keys = redisTemplate.keys(ACTIVE_KEY_PREFIX + "*");
        return keys != null ? (long) keys.size() : 0L;
    }
}