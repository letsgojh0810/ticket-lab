package com.example.ticket.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventConsumer {

    /**
     * 예약 이벤트를 소비하여 후속 처리 수행
     * - 사용자 알림 발송 (이메일, SMS, 푸시)
     * - 예약 통계 집계
     * - 데이터 분석용 이벤트 저장
     *
     * @param message 이벤트 메시지 (JSON)
     * @param partition 파티션 번호
     * @param offset 오프셋
     * @param acknowledgment 수동 커밋용
     */
    @KafkaListener(
            topics = "reservation-events",
            groupId = "ticket-reservation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        try {
            log.info("📨 이벤트 수신 - Partition: {}, Offset: {}, Message: {}",
                    partition, offset, message);

            // JSON 파싱 및 이벤트 타입 추출
            processEvent(message);

            // 성공 시 수동 커밋
            acknowledgment.acknowledge();
            log.info("✅ 이벤트 처리 완료 및 커밋 - Offset: {}", offset);

        } catch (Exception e) {
            log.error("❌ 이벤트 처리 실패 - Partition: {}, Offset: {}, Error: {}",
                    partition, offset, e.getMessage());
            // 실패 시 커밋하지 않음 → 재처리됨
            // 실제 운영 환경에서는 DLQ(Dead Letter Queue)로 이동 고려
        }
    }

    /**
     * 이벤트 타입별 처리 로직
     */
    private void processEvent(String message) {
        // TODO: JSON 라이브러리 (Jackson, Gson) 사용하여 파싱
        // 현재는 로깅만 수행 (실제 구현 시 확장)

        if (message.contains("RESERVATION_SUCCESS")) {
            handleReservationSuccess(message);
        } else if (message.contains("RESERVATION_FAILED")) {
            handleReservationFailed(message);
        } else if (message.contains("RESERVATION_CANCELLED")) {
            handleReservationCancelled(message);
        }
    }

    /**
     * 예약 성공 이벤트 처리
     */
    private void handleReservationSuccess(String message) {
        log.info("🎉 예약 성공 처리 시작");
        // 1. 사용자에게 예약 확인 이메일 발송
        // 2. 예약 통계 업데이트 (Redis 카운터 증가)
        // 3. 분석용 데이터 웨어하우스 전송
        log.info("📧 예약 확인 알림 발송 (이메일/SMS)");
        log.info("📊 예약 통계 업데이트");
    }

    /**
     * 예약 실패 이벤트 처리
     */
    private void handleReservationFailed(String message) {
        log.info("⚠️ 예약 실패 처리 시작");
        // 1. 실패 사유 분석 (락 타임아웃, 중복 예약 등)
        // 2. 실패 통계 집계
        // 3. 필요시 사용자에게 재시도 안내
        log.info("📊 실패 통계 업데이트");
    }

    /**
     * 예약 취소 이벤트 처리
     */
    private void handleReservationCancelled(String message) {
        log.info("🔄 예약 취소 처리 시작");
        // 1. 좌석 재오픈 알림 발송
        // 2. 환불 프로세스 시작
        // 3. 취소 통계 업데이트
        log.info("💳 환불 프로세스 시작");
    }
}
