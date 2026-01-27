package com.example.ticket.application;

import com.example.ticket.domain.reservation.ReservationRepository;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ReservationFacadeTest {

    @Autowired
    private ReservationFacade reservationFacade;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatRepository seatRepository;

    private Long targetSeatId;

    @BeforeEach
    void setUp() {
        // 테스트용 좌석 생성 (ID 1번)
        Seat seat = new Seat("A1");
        Seat savedSeat = seatRepository.save(seat);
        targetSeatId = savedSeat.getId();

        // 데이터 초기화
        reservationRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 100명이 예약 시도 시 1명만 성공해야 한다")
    void concurrency_test() throws InterruptedException {
        int threadCount = 100;
        // 32개의 스레드가 번갈아가며 100개의 요청 처리
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        // 모든 스레드가 준비될 때까지 기다렸다가 한꺼번에 출발시키기 위한 장치
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    String result = reservationFacade.reserve(targetSeatId, userId);
                    if (result.equals("SUCCESS")) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 요청이 끝날 때까지 대기

        System.out.println("성공 횟수: " + successCount.get());
        System.out.println("실패 횟수: " + failCount.get());

        // 검증: 성공은 무조건 1번이어야 함
        assertEquals(1, successCount.get());
        // 검증: 예약 테이블에 저장된 데이터도 딱 1개여야 함
        assertEquals(1, reservationRepository.count());
    }
}