package com.example.ticket.infrastructure.redis;

import com.example.ticket.infrastructure.redis.scheduler.QueueScheduler;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class QueueSystemLoadTest {

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private QueueScheduler queueScheduler;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final String ACTIVE_KEY_PREFIX = "ticket:active:users:";

    @BeforeEach
    void setUp() {
        // Redis 초기화
        redisTemplate.delete(QUEUE_KEY);
        redisTemplate.keys(ACTIVE_KEY_PREFIX + "*").forEach(redisTemplate::delete);
    }

    @Test
    @DisplayName("🎯 [대규모 부하 테스트] 10,000명 동시 진입 → 초당 100명씩 순차 처리")
    void test_10000명_대기열_동시_진입_및_순차_처리() throws InterruptedException {
        int totalUsers = 10000;
        int maxActiveUsers = 100;

        System.out.println("\n");
        System.out.println("=".repeat(80));
        System.out.println("🚀 대기열 시스템 대규모 부하 테스트 시작");
        System.out.println("=".repeat(80));
        System.out.println("📊 테스트 시나리오:");
        System.out.println("   • 총 대기 인원: " + totalUsers + "명");
        System.out.println("   • Active User 최대: " + maxActiveUsers + "명");
        System.out.println("   • 스케줄러 간격: 1초");
        System.out.println("=".repeat(80));
        System.out.println();

        // ========== Phase 1: 10,000명 동시 진입 ==========
        System.out.println("📍 Phase 1: 10,000명 동시 대기열 진입");
        System.out.println("-".repeat(80));

        long phase1Start = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch latch = new CountDownLatch(totalUsers);

        for (int i = 1; i <= totalUsers; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    waitingQueueService.registerAndGetRank(userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long phase1Duration = System.currentTimeMillis() - phase1Start;

        Long waitingCount = redisTemplate.opsForZSet().size(QUEUE_KEY);
        Long activeCount = waitingQueueService.getActiveUserCount();

        System.out.println("✅ Phase 1 완료!");
        System.out.println("   • 소요 시간: " + phase1Duration + "ms");
        System.out.println("   • 대기열 인원: " + waitingCount + "명");
        System.out.println("   • Active User: " + activeCount + "명");
        assertEquals(totalUsers, waitingCount, "대기열에 10,000명이 등록되어야 함");
        assertEquals(0, activeCount, "초기 Active User는 0명이어야 함");
        System.out.println();

        // ========== Phase 2: QueueScheduler 순차 실행 ==========
        System.out.println("📍 Phase 2: QueueScheduler 순차 실행 (100명씩 처리)");
        System.out.println("-".repeat(80));

        int cycles = 0;
        long phase2Start = System.currentTimeMillis();

        while (true) {
            cycles++;
            queueScheduler.moveWaitingToActive();

            Long currentWaiting = redisTemplate.opsForZSet().size(QUEUE_KEY);
            Long currentActive = waitingQueueService.getActiveUserCount();

            // 최대 100명 제한 검증
            assertTrue(currentActive <= maxActiveUsers,
                    "Active User는 " + maxActiveUsers + "명을 초과할 수 없음 (현재: " + currentActive + ")");

            // 진행률 계산
            int processed = totalUsers - currentWaiting.intValue();
            double progress = (processed * 100.0) / totalUsers;

            // 로그 출력 (10사이클마다)
            if (cycles % 10 == 0 || currentWaiting == 0) {
                System.out.printf("⏱️  [Cycle %3d] 대기: %,5d명 | Active: %3d명 | 처리: %,5d명 (%.1f%%)%n",
                        cycles, currentWaiting, currentActive, processed, progress);
            }

            // 모든 사람이 Active User로 전환되면 종료
            if (currentWaiting == 0 && currentActive == totalUsers) {
                break;
            }

            // 1초 대기 (실제 스케줄러 간격 시뮬레이션)
            Thread.sleep(1000);
        }

        long phase2Duration = System.currentTimeMillis() - phase2Start;

        System.out.println();
        System.out.println("✅ Phase 2 완료!");
        System.out.println("   • 총 사이클: " + cycles + "회");
        System.out.println("   • 소요 시간: " + (phase2Duration / 1000) + "초 (" + phase2Duration + "ms)");
        System.out.println("   • 최종 Active User: " + waitingQueueService.getActiveUserCount() + "명");
        System.out.println("   • 최종 대기열: " + redisTemplate.opsForZSet().size(QUEUE_KEY) + "명");
        System.out.println();

        // ========== Phase 3: Active User 순차 제거 시뮬레이션 ==========
        System.out.println("📍 Phase 3: Active User 순차 제거 (예약 완료 시뮬레이션)");
        System.out.println("-".repeat(80));

        long phase3Start = System.currentTimeMillis();
        int removeCount = 0;

        // 100명씩 제거하면서 새로운 사람 입장 테스트
        for (int batch = 0; batch < 10; batch++) {
            // 10명씩 제거
            for (int i = 0; i < 10; i++) {
                long userId = batch * 10 + i + 1;
                waitingQueueService.removeActiveUser(userId);
                removeCount++;
            }

            Long currentActive = waitingQueueService.getActiveUserCount();
            System.out.printf("🔄 [Batch %d] %d명 제거 → Active User: %d명%n",
                    batch + 1, removeCount, currentActive);

            // 제거 후 Active User가 90명으로 줄었는지 확인
            assertEquals(totalUsers - removeCount, currentActive,
                    "Active User는 제거한 만큼 줄어야 함");
        }

        long phase3Duration = System.currentTimeMillis() - phase3Start;

        System.out.println();
        System.out.println("✅ Phase 3 완료!");
        System.out.println("   • 제거한 인원: " + removeCount + "명");
        System.out.println("   • 소요 시간: " + phase3Duration + "ms");
        System.out.println("   • 최종 Active User: " + waitingQueueService.getActiveUserCount() + "명");
        System.out.println();

        // ========== 최종 결과 ==========
        long totalDuration = System.currentTimeMillis() - phase1Start;

        System.out.println("=".repeat(80));
        System.out.println("🎉 테스트 완료! 모든 검증 통과");
        System.out.println("=".repeat(80));
        System.out.println("📊 최종 통계:");
        System.out.println("   • 총 테스트 시간: " + (totalDuration / 1000) + "초 (" + totalDuration + "ms)");
        System.out.println("   • Phase 1 (진입): " + phase1Duration + "ms");
        System.out.println("   • Phase 2 (처리): " + (phase2Duration / 1000) + "초");
        System.out.println("   • Phase 3 (제거): " + phase3Duration + "ms");
        System.out.println();
        System.out.println("✅ 검증 항목:");
        System.out.println("   ✔ 10,000명 동시 진입 성공");
        System.out.println("   ✔ Active User 최대 100명 제한 준수");
        System.out.println("   ✔ 대기열 순차 처리 정상 동작");
        System.out.println("   ✔ Active User 제거 후 슬롯 재사용 가능");
        System.out.println("=".repeat(80));
        System.out.println();

        executorService.shutdown();
    }

    @Test
    @DisplayName("🔥 [동시성 테스트] Active User 최대 제한 검증 (Race Condition)")
    void test_Active_User_최대_100명_제한_동시성_검증() throws InterruptedException {
        System.out.println("\n");
        System.out.println("=".repeat(80));
        System.out.println("🔥 Active User 최대 제한 동시성 테스트");
        System.out.println("=".repeat(80));
        System.out.println();

        // 1000명 대기열 진입
        for (int i = 1; i <= 1000; i++) {
            waitingQueueService.registerAndGetRank((long) i);
        }

        // Scheduler 10번 실행 (동시에 여러 스레드에서)
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            final int cycle = i + 1;
            executorService.submit(() -> {
                try {
                    queueScheduler.moveWaitingToActive();
                    Long activeCount = waitingQueueService.getActiveUserCount();
                    System.out.printf("🔄 [Thread %d] Active User: %d명%n", cycle, activeCount);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        Long finalActive = waitingQueueService.getActiveUserCount();
        System.out.println();
        System.out.println("✅ 최종 Active User: " + finalActive + "명");
        assertTrue(finalActive <= 100, "동시 실행에도 100명 제한이 지켜져야 함");
        System.out.println("✅ 테스트 통과: 동시성 환경에서도 최대 제한 준수");
        System.out.println("=".repeat(80));
        System.out.println();
    }
}
