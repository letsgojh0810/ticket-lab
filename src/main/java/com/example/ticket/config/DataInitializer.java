package com.example.ticket.config;

import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SeatRepository seatRepository;

    @Override
    public void run(String... args) {
        if (seatRepository.count() > 0) {
            System.out.println("✅ [System] 좌석 데이터 이미 존재, 초기화 생략");
            return;
        }
        for (int i = 1; i <= 100; i++) {
            seatRepository.save(new Seat(i + "번 좌석"));
        }
        System.out.println("✅ [System] 테스트용 좌석 100개 생성 완료!");
    }
}