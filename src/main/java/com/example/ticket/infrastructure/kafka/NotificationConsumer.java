package com.example.ticket.infrastructure.kafka;

import com.example.ticket.domain.event.ReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
// import org.springframework.mail.SimpleMailMessage;
// import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationConsumer {

    // private final JavaMailSender mailSender; // SMTP 비활성화 - 로그로 대체
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
        // SMTP 비활성화: 실제 메일 전송 대신 로그로 대체

        String recipient = "honeyguardians@gmail.com"; // 실무에서는 유저 DB 조회
        String subject = "[티켓 예약 성공] 좌석 예매가 완료되었습니다! 🎉";

        String content = String.format(
                "안녕하세요! %d번 회원님.\n\n" +
                        "요청하신 [%s] 좌석의 예매가 성공적으로 완료되었습니다.\n" +
                        "즐거운 관람 되시길 바랍니다!",
                event.getUserId(), event.getSeatNumber()
        );

        // 로그로 메일 내용 출력
        log.info("📧 [이메일 시뮬레이션]");
        log.info("   수신자: {}", recipient);
        log.info("   제목: {}", subject);
        log.info("   내용:\n{}", content);
        log.info("✅ [알림 처리 완료] userId={}, seatNumber={}", event.getUserId(), event.getSeatNumber());

        /* SMTP 활성화 시 아래 코드 사용
        SimpleMailMessage mailMessage = new SimpleMailMessage();
        mailMessage.setTo(recipient);
        mailMessage.setSubject(subject);
        mailMessage.setText(content);
        mailSender.send(mailMessage);
        log.info("✅ [이메일 발송 완료] 수신자: {}, 좌석: {}", recipient, event.getSeatNumber());
        */
    }
}