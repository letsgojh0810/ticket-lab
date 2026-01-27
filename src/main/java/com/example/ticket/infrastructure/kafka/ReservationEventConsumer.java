package com.example.ticket.infrastructure.kafka;

import com.example.ticket.domain.event.ReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventConsumer {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()); // 날짜 파싱을 위해 필요

    @KafkaListener(topics = "reservation-events", groupId = "ticket-reservation-group")
    public void consume(@Payload String message, Acknowledgment acknowledgment) {
        try {
            // 1. JSON 문자열을 다시 자바 객체(ReservationEvent)로 변환! (역직렬화)
            ReservationEvent event = objectMapper.readValue(message, ReservationEvent.class);

            log.info("📩 [알림 서비스] 이벤트 수신 완료! (타입: {})", event.getEventType());

            // 2. 이벤트 타입에 따른 비즈니스 로직 실행
            if (event.getEventType() == ReservationEvent.EventType.RESERVATION_SUCCESS) {
                sendKakaoTalk(event);
            }

            acknowledgment.acknowledge(); // 처리 완료 신고!
        } catch (Exception e) {
            log.error("❌ 알림 처리 중 에러 발생: {}", e.getMessage());
        }
    }

    private void sendKakaoTalk(ReservationEvent event) {
        log.info("📱 [알림톡 발송] --------------------------------");
        log.info("📱 수신자(UserId): {}", event.getUserId());
        log.info("📱 좌석 정보: {}", event.getSeatNumber());
        log.info("📱 메시지: 축하합니다! 예매가 성공적으로 완료되었습니다.");
        log.info("📱 [발송 완료] --------------------------------");
    }
}