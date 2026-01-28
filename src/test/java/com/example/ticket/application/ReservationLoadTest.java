package com.example.ticket.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class ReservationLoadTest {

    @Autowired
    private ReservationFacade reservationFacade;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Test
    void test_1000_users_full_flow() throws InterruptedException {
        int userCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch latch1 = new CountDownLatch(userCount);

        // [STEP 1] 1000명이 동시에 번호표 뽑기
        System.out.println("=== [STEP 1] 1000명 대기열 진입 시작 ===");
        for (int i = 1; i <= userCount; i++) {
            Long userId = (long) i;
            executorService.submit(() -> {
                try {
                    String result = reservationFacade.reserve(1L, userId);
                    System.out.println("User " + userId + " -> " + result);
                } finally {
                    latch1.countDown();
                }
            });
        }
        latch1.await(); // 1000명 모두 번호표 받을 때까지 대기

        // [STEP 2] 스케줄러가 사람들을 들여보낼 시간을 줍니다.
        System.out.println("\n=== [STEP 2] 스케줄러 작동 대기 (3초) ===");
        Thread.sleep(3000);
        // 1초에 10명씩이면 3초면 30명 정도가 'Active' 상태가 되었을 겁니다.

        // [STEP 3] 상위 유저들이 다시 예약을 시도합니다.
        System.out.println("\n=== [STEP 3] 상위 유저 재요청 (예약 시도) ===");
        int retryCount = 30; // 상위 30명만 다시 시도
        CountDownLatch latch2 = new CountDownLatch(retryCount);

        for (int i = 1; i <= retryCount; i++) {
            Long userId = (long) i;
            executorService.submit(() -> {
                try {
                    String result = reservationFacade.reserve(1L, userId);
                    System.out.println("User " + userId + " (재시도) -> " + result);
                } finally {
                    latch2.countDown();
                }
            });
        }

        latch2.await();
        System.out.println("\n✅ 모든 테스트 시나리오 종료!");
    }
}
