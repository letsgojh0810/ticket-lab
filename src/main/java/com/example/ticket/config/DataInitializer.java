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
        // 테스트를 위해 1번부터 10번 좌석까지 미리 생성
        for (int i = 1; i <= 10; i++) {
            seatRepository.save(new Seat(i + "번 좌석"));
        }
        System.out.println("✅ [System] 테스트용 좌석 10개 생성 완료!");
    }
}