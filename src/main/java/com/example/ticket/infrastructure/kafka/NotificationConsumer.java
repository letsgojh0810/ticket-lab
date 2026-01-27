package com.example.ticket.infrastructure.kafka;

import com.example.ticket.domain.event.ReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationConsumer {

    private final JavaMailSender mailSender; // application.properties 설정이 자동 주입됨
    private final ObjectMapper objectMapper; // JSON 변환기

    @KafkaListener(topics = "reservation-events", groupId = "ticket-reservation-group")
    public void consume(String message, Acknowledgment acknowledgment) {
        try {
            log.info("📥 카프카 메시지 수신: {}", message);

            // 1. JSON 문자열을 객체로 변환
            ReservationEvent event = objectMapper.readValue(message, ReservationEvent.class);

            if (event.getEventType() == ReservationEvent.EventType.RESERVATION_SUCCESS) {
                log.info("🚀 [통과] Enum 타입 비교 성공!");
                sendEmail(event);
            }

            // 3. 메시지 처리 완료 알림 (Offset Commit)
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("❌ 알림 처리 중 에러 발생: {}", e.getMessage());
            // 여기서 acknowledge를 안 하면, 실패한 메시지는 나중에 다시 시도하게 됩니다.
        }
    }

    private void sendEmail(ReservationEvent event) {
        SimpleMailMessage mailMessage = new SimpleMailMessage();

        // 💡 테스트를 위해 일단 본인 이메일을 적으세요!
        // 실무에선 event.getUserId()로 유저 DB를 조회해서 이메일을 가져옵니다.
        mailMessage.setTo("honeyguardians@gmail.com");
        mailMessage.setSubject("[티켓 예약 성공] 좌석 예매가 완료되었습니다! 🎉");

        String content = String.format(
                "안녕하세요! %d번 회원님.\n\n" +
                        "요청하신 [%s] 좌석의 예매가 성공적으로 완료되었습니다.\n" +
                        "즐거운 관람 되시길 바랍니다!",
                event.getUserId(), event.getSeatNumber()
        );

        mailMessage.setText(content);

        // 실제 발송! (네트워크 통신이 일어나므로 시간이 조금 걸릴 수 있습니다)
        mailSender.send(mailMessage);

        log.info("✅ [이메일 발송 완료] 수신자: {}, 좌석: {}", "본인메일", event.getSeatNumber());
    }
}