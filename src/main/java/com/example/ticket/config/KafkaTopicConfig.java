package com.example.ticket.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 토픽 설정
 *
 * 애플리케이션 시작 시 토픽이 자동 생성됩니다.
 * 이미 존재하는 토픽은 무시됩니다 (설정 변경 X).
 *
 * 토픽 설계 원칙:
 * - 파티션 수 = 예상 Consumer 인스턴스 수 이상
 * - 같은 좌석 이벤트는 같은 파티션으로 (순서 보장)
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * 예약 이벤트 토픽
     *
     * 용도: 예약 성공/실패 이벤트 발행
     * 파티션 키: seatId (같은 좌석 → 같은 파티션 → 순서 보장)
     *
     * 파티션 수: 3
     * - Consumer 3대까지 병렬 처리 가능
     * - 필요시 확장 가능 (축소는 불가)
     *
     * 복제본: 1 (개발 환경)
     * - 운영 환경에서는 3으로 설정 권장
     */
    @Bean
    public NewTopic reservationEventsTopic() {
        return TopicBuilder.name("reservation-events")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", "604800000")  // 7일 보관
                .config("cleanup.policy", "delete")   // 오래된 메시지 삭제
                .build();
    }

}
