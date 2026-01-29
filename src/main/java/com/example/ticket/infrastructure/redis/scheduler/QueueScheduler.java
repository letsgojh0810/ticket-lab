package com.example.ticket.infrastructure.redis.scheduler;

import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
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
    private final WaitingQueueService waitingQueueService;
    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final int MAX_ACTIVE_USERS = 100; // 최대 동시 처리 가능 인원

    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    public void moveWaitingToActive() {
        // 현재 Active User 수 확인 (개별 키 카운트)
        Long currentActive = waitingQueueService.getActiveUserCount();

        // 빈 자리 계산
        long availableSlots = MAX_ACTIVE_USERS - currentActive;
        if (availableSlots <= 0) {
            return; // 자리 없으면 스킵
        }

        // 빈 자리만큼만 대기열에서 입장
        Set<String> waitingUsers = redisTemplate.opsForZSet().range(QUEUE_KEY, 0, availableSlots - 1);

        if (waitingUsers != null && !waitingUsers.isEmpty()) {
            // 각 유저를 개별 키 + TTL로 Active User 등록
            for (String userIdStr : waitingUsers) {
                Long userId = Long.parseLong(userIdStr);
                waitingQueueService.addActiveUser(userId);
            }

            // 대기열에서 제거
            redisTemplate.opsForZSet().remove(QUEUE_KEY, waitingUsers.toArray(new String[0]));

            log.info("🚀 대기열 -> 활성유저 전환: {}명 입장 완료 (현재 활성: {}/{}명)",
                    waitingUsers.size(), currentActive + waitingUsers.size(), MAX_ACTIVE_USERS);
        }
    }
}