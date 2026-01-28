package com.example.ticket.infrastructure.redis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaitingQueueService {
    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final String ACTIVE_KEY = "ticket:active:users";

    // 대기열 등록 및 순번 확인
    public Long registerAndGetRank(Long userId) {
        String userIdStr = userId.toString();
        redisTemplate.opsForZSet().add(QUEUE_KEY, userIdStr, (double) System.currentTimeMillis());
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, userIdStr);
        return (rank != null) ? rank + 1 : -1L;
    }

    // 입장 허가 여부 확인
    public boolean isAllowed(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(ACTIVE_KEY, userId.toString()));
    }

    // 입장 권한 반납 (결제 완료/실패 시 호출)
    public void removeActiveUser(Long userId) {
        redisTemplate.opsForSet().remove(ACTIVE_KEY, userId.toString());
    }
}