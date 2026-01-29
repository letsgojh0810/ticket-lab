package com.example.ticket.domain.reservation;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    // 50%의 확률로 결제 성공 여부를 반환
    public boolean processPayment() {
        // P(success) = 0.5
        return Math.random() < 0.8;
    }
}