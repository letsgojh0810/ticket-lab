package com.example.ticket.infrastructure.redis.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueScheduler {
    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final String ACTIVE_KEY = "ticket:active:users";

    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    public void moveWaitingToActive() {
        // 한 번에 10명씩 입장시킴
        Set<String> waitingUsers = redisTemplate.opsForZSet().range(QUEUE_KEY, 0, 9);

        if (waitingUsers != null && !waitingUsers.isEmpty()) {
            redisTemplate.opsForSet().add(ACTIVE_KEY, waitingUsers.toArray(new String[0]));
            redisTemplate.opsForZSet().remove(QUEUE_KEY, waitingUsers.toArray(new String[0]));
            log.info("🚀 대기열 -> 활성유저 전환: {}명 입장 완료", waitingUsers.size());
        }
    }
}