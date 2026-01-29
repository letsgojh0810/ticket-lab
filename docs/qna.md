# NHN 링크 백엔드 운영직무 - 기술면접 예상 질문 & 모범답변

---

## 🔴 Category 1: 동시성 & 분산 락

### Q1. 현재 락 타임아웃이 1초 대기, 2초 점유인데, 이 값을 어떻게 결정했나요? 운영 환경에서는 어떻게 조정하시겠어요?

#### 📌 모범 답변
"학습 단계에서는 빠른 응답을 위해 짧게 설정했습니다. 실제 운영에서는 **부하 테스트 결과**를 기반으로 결정해야 합니다.

**대기 시간 산정:**
- Redis 조회 평균: 5ms
- DB 조회 평균: 20ms
- 네트워크 지연 여유: 100ms
- **최소 150ms 이상** 필요

**점유 시간 산정:**
- Redis 업데이트 시간 측정
- 로그 기록 시간 포함
- 실측값의 2배 여유 확보

**동적 조정 전략:**
Prometheus로 락 획득 실패율을 모니터링하면서, 실패율이 5% 이상이면 대기 시간을 점진적으로 늘립니다. 예를 들어 1초 → 1.5초 → 2초 순으로 조정하면서 최적값을 찾습니다."

#### 🔄 꼬리질문 1: "락 대기 시간을 너무 길게 하면 어떤 문제가 생기나요?"
**답변:**
"사용자 경험이 나빠집니다. 예를 들어 5초 대기 설정 시, 이미 예약된 좌석에 접근한 사용자도 5초간 기다린 후 실패 응답을 받게 됩니다. 또한 대기 중인 스레드가 많아져서 서버 리소스(Thread Pool)가 고갈될 수 있습니다. 따라서 **빠른 실패(Fail Fast)** 원칙으로 1~3초 사이가 적절합니다."

#### 🔄 꼬리질문 2: "락을 아예 안 쓰고 DB의 Optimistic Lock만 사용하면 안 되나요?"
**답변:**
"Optimistic Lock은 충돌 발생 시 재시도해야 해서, 동시 접속이 많은 티켓팅에서는 **재시도 폭풍(Retry Storm)**이 발생합니다. 예를 들어 1000명이 동시 접근 시 999명이 실패 후 재시도하면서 DB 부하가 급증합니다. 분산 락은 큐 방식으로 순차 처리해서 DB 부하를 최소화합니다."

---

### Q2. 좌석 69행에서 락을 조기에 해제하는데, 그 사이에 다른 요청이 같은 좌석을 선점하려고 하면 어떻게 되나요? Redis 업데이트가 완료되기 전에 경합이 발생할 수 있지 않나요?

#### 📌 모범 답변
"좋은 지적입니다. 코드 흐름을 보면:

**67행:** `seatCacheService.updateSeatStatus(seatId, SELECTED, 5분)`
**69-73행:** 락 해제

Redis 업데이트가 **완료된 후**에 락을 해제하므로, 다음 요청은 57행에서 이미 `SELECTED` 상태를 확인하고 '다른 사용자 결제 진행 중' 메시지를 받습니다.

**만약 Redis 업데이트 도중 장애가 발생하면:**
락 해제 후 다른 요청이 `AVAILABLE` 상태로 읽을 수 있습니다. 이를 방지하려면:

1. **Redis 트랜잭션(MULTI/EXEC)** 사용
2. **Lua 스크립트**로 원자성 보장
3. 또는 락 해제를 완전히 확인된 후로 조정

현재 구조는 Redis 단일 SET 명령이 원자적(5ms 이내)이라는 가정 하에 설계되었습니다."

#### 🔄 꼬리질문 1: "Redis Lua 스크립트는 어떻게 작성하나요?"
**답변:**
```lua
-- Redis에서 원자적으로 상태 확인 + 업데이트
local current = redis.call('GET', KEYS[1])
if current == 'AVAILABLE' or current == nil then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 1
else
    return 0
end
```

Java에서 호출:
```java
redisTemplate.execute(
    new DefaultRedisScript<>(luaScript, Long.class),
    Collections.singletonList("state:seat:" + seatId),
    "SELECTED", "300"
);
```

이렇게 하면 상태 확인과 업데이트가 원자적으로 처리됩니다."

#### 🔄 꼬리질문 2: "조기 락 해제의 실제 성능 개선 효과는 얼마나 되나요?"
**답변:**
"락 점유 시간을 2초에서 0.1초로 줄이면, **동시 처리량(Throughput)이 20배** 증가합니다.

**계산:**
- 기존: 1초당 0.5개 처리 (2초 점유)
- 개선: 1초당 10개 처리 (0.1초 점유)

실제로는 락 대기자가 많을수록 효과가 커집니다. 부하 테스트로 TPS(Transaction Per Second)를 측정하면 명확히 확인할 수 있습니다."

---

## 🟠 Category 2: Redis & 대기열

### Q3. 대기열 시스템에서 Active User를 Set으로 관리하는데, 만약 사용자가 결제를 완료하지 않고 브라우저를 닫으면 Set에서 제거가 안 되지 않나요? 메모리 누수 문제는 어떻게 해결하시겠어요?

#### 📌 모범 답변
"맞습니다. 현재 코드에는 **세션 타임아웃 처리가 없어서** Active Set이 계속 쌓일 수 있습니다.

**해결 방법 3가지:**

**1. TTL 기반 자동 만료 (권장)**
```java
// Set 대신 Hash + Field별 TTL
redisTemplate.opsForHash().put("active:users", userId.toString(),
    String.valueOf(System.currentTimeMillis()));
redisTemplate.expire("active:users:" + userId, 5, TimeUnit.MINUTES);
```

**2. Heartbeat 방식**
- 프론트엔드에서 30초마다 `/heartbeat` API 호출
- 호출 없으면 자동으로 Active Set에서 제거
- WebSocket이나 Server-Sent Events로 구현 가능

**3. Scheduler 정리 작업**
```java
@Scheduled(fixedRate = 600000) // 10분마다
public void cleanupInactiveUsers() {
    Set<String> activeUsers = redisTemplate.opsForSet().members("ticket:active:users");
    long now = System.currentTimeMillis();

    for (String userId : activeUsers) {
        Long lastActivity = getLastActivityTime(userId);
        if (now - lastActivity > 300000) { // 5분 초과
            waitingQueueService.removeActiveUser(Long.parseLong(userId));
        }
    }
}
```

운영 환경에서는 **1번 + 3번 조합**이 가장 안전합니다."

#### 🔄 꼬리질문 1: "Redis 메모리가 부족하면 어떤 Eviction Policy를 사용하시겠어요?"
**답변:**
"대기열 시스템에서는 **allkeys-lru**를 추천합니다.

**Eviction Policy 종류:**
- `noeviction`: 메모리 가득 차면 쓰기 거부 (기본값)
- `allkeys-lru`: 모든 키 중 가장 오래된 것 삭제
- `volatile-lru`: TTL 설정된 키 중 오래된 것 삭제
- `allkeys-random`: 무작위 삭제

**선택 이유:**
대기열 데이터는 모두 일시적이므로, TTL 여부 관계없이 오래된 데이터를 삭제해도 비즈니스에 영향이 적습니다. 다만 운영에서는 Redis 메모리를 충분히 확보하고, 80% 도달 시 알림을 받도록 모니터링합니다."

#### 🔄 꼬리질문 2: "Active User 한도를 100명으로 설정했는데, 이 숫자는 어떻게 정하나요?"
**답변:**
"서버 처리 용량을 기준으로 계산합니다.

**계산식:**
```
Active User 한도 = (서버 CPU 코어 수 × 코어당 처리량) / 안전 계수

예: 8 코어 × 20 TPS / 2 = 80명
```

**고려사항:**
- DB 커넥션 풀 크기 (HikariCP 기본 10개)
- 평균 결제 시간 (10초)
- 서버 인스턴스 수 (3대면 한도를 1/3로)

실제로는 부하 테스트로 **CPU 70%, 메모리 80% 이하**를 유지하는 선에서 결정합니다."

---

### Q4. Redis에 저장된 좌석 상태(state:seat:{id})와 MySQL의 Seat 테이블 상태가 불일치하면 어떻게 감지하고 복구하나요?

#### 📌 모범 답변
"데이터 정합성 문제입니다. 발생 가능한 시나리오:

**불일치 발생 케이스:**
1. Redis 업데이트 성공 → DB 트랜잭션 롤백
2. DB 커밋 성공 → Redis 업데이트 실패
3. Redis 장애로 데이터 유실

**대응 방안:**

**1. DB를 Source of Truth로 간주 (현재 설계)**
- Redis는 성능 최적화용 캐시일 뿐
- TTL 5분 설정으로 자동 복구
- 불일치 발생 시 DB 조회로 Fallback

**2. 정합성 검증 Scheduler**
```java
@Scheduled(cron = "0 */10 * * * *") // 10분마다
public void validateCacheConsistency() {
    List<Seat> dbSeats = seatRepository.findAll();

    for (Seat seat : dbSeats) {
        String redisStatus = seatCacheService.getSeatStatus(seat.getId());
        String dbStatus = seat.getStatus().name();

        if (!redisStatus.equals(dbStatus)) {
            log.warn("정합성 불일치 발견: seatId={}, Redis={}, DB={}",
                seat.getId(), redisStatus, dbStatus);

            // Redis를 DB 상태로 동기화
            seatCacheService.updateSeatStatus(seat.getId(), dbStatus, 0);
        }
    }
}
```

**3. 이벤트 소싱 패턴 (고급)**
- 모든 상태 변경을 Kafka 이벤트로 기록
- 이벤트 재생(Replay)으로 상태 복구
- Kafka를 Source of Truth로 사용

현재는 1번 방식이며, 운영 환경에서는 **2번 Scheduler 추가**를 권장합니다."

#### 🔄 꼬리질문 1: "Scheduler가 대량의 데이터를 조회하면 DB 부하가 생기지 않나요?"
**답변:**
"맞습니다. 최적화 방법:

**1. 페이징 처리**
```java
int pageSize = 100;
int page = 0;

while (true) {
    Page<Seat> seats = seatRepository.findAll(
        PageRequest.of(page++, pageSize));

    // 검증 로직

    if (!seats.hasNext()) break;
    Thread.sleep(1000); // 1초 대기로 부하 분산
}
```

**2. 변경된 데이터만 검증**
```java
// updated_at이 최근 10분 이내인 것만
@Query("SELECT s FROM Seat s WHERE s.updatedAt > :since")
List<Seat> findRecentlyUpdated(@Param("since") LocalDateTime since);
```

**3. Read Replica 사용**
검증 쿼리는 읽기 전용 Replica DB에서 실행해서 주 DB 부하 방지"

#### 🔄 꼬리질문 2: "Redis와 DB를 동시에 업데이트하는 트랜잭션은 불가능한가요?"
**답변:**
"기술적으로는 가능하지만 권장하지 않습니다.

**XA 트랜잭션 (2-Phase Commit):**
- Redis와 MySQL을 모두 트랜잭션으로 묶음
- 하나라도 실패하면 전체 롤백
- **단점:** 성능 저하 심각, 구현 복잡도 높음

**Saga 패턴:**
- Redis 업데이트 → 성공 시 DB 업데이트
- DB 실패 시 Redis 보상 트랜잭션(Compensating Transaction)
- **단점:** 일시적 불일치 허용해야 함

**실무 권장:**
DB를 정합성의 기준으로 삼고, Redis는 캐시로만 사용해서 불일치를 허용하는 것이 더 실용적입니다."

---

## 🟡 Category 3: Kafka & 이벤트 기반

### Q5. Kafka Consumer가 메시지를 읽다가 중간에 죽으면, 재시작 시 어디서부터 다시 읽나요? 중복 처리는 어떻게 방지하나요?

#### 📌 모범 답변
"Kafka는 **Consumer Group의 Offset**을 관리합니다.

**재시작 시 동작:**
- `auto.offset.reset=earliest`: 최초 실행 시 가장 오래된 메시지부터
- 이미 처리한 메시지는 **커밋된 오프셋** 이후부터 재개
- 커밋 방식: `MANUAL_IMMEDIATE`로 처리 성공 후 수동 커밋

**중복 처리 문제:**
현재 설정은 **At-least-once** 보장 → 중복 가능성 있음

**해결: 멱등성(Idempotency) 구현**
```java
@Transactional
public void processEvent(ReservationEvent event) {
    // 1. 이미 처리된 이벤트인지 확인
    if (processedEventRepository.existsByEventId(event.getEventId())) {
        log.info("이미 처리된 이벤트 스킵: {}", event.getEventId());
        return; // 중복 제거
    }

    // 2. 비즈니스 로직 처리
    emailService.sendConfirmation(event.getUserId());

    // 3. 처리 완료 기록 (영구 저장)
    processedEventRepository.save(
        new ProcessedEvent(event.getEventId(), LocalDateTime.now()));
}
```

**ProcessedEvent 테이블:**
```sql
CREATE TABLE processed_events (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP
);

-- 7일 지난 데이터는 Scheduler로 삭제
```

운영에서는 **Event ID 기반 중복 제거**가 필수입니다."

#### 🔄 꼬리질문 1: "Exactly-once 처리는 불가능한가요?"
**답변:**
"Kafka Streams나 Transactional Producer를 사용하면 가능하지만, 복잡도가 높습니다.

**Kafka Transactional API:**
```java
@Bean
public ProducerFactory<String, String> producerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "tx-producer-1");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    return new DefaultKafkaProducerFactory<>(config);
}
```

**트레이드오프:**
- Exactly-once: 성능 저하 (배치 처리 필수)
- At-least-once + 멱등성: 구현 간단, 성능 우수

**실무에서는** 알림/통계 같은 부가 기능은 At-least-once로 처리하고, 결제 같은 핵심 기능은 DB 트랜잭션으로 보장합니다."

#### 🔄 꼬리질문 2: "Consumer Group의 파티션 리밸런싱은 언제 발생하나요?"
**답변:**
**리밸런싱 발생 시점:**
1. Consumer 추가/제거 (스케일 아웃/인)
2. Consumer 장애 (Heartbeat 끊김)
3. 파티션 수 변경

**문제점:**
- 리밸런싱 중에는 메시지 처리 중단 (Stop-the-world)
- 처리 중이던 메시지 재처리 가능

**최소화 방법:**
```properties
# Heartbeat 간격 짧게 (빠른 장애 감지)
heartbeat.interval.ms=3000
session.timeout.ms=10000

# 처리 시간 여유 확보
max.poll.interval.ms=300000  # 5분
```

현재 프로젝트는 단일 Consumer라서 리밸런싱이 없지만, 운영에서는 3개 Consumer + 3개 파티션으로 구성하면 안정적입니다."

---

### Q6. ReservationEvent를 JSON으로 직렬화하는데, 나중에 Event 필드를 추가하면 하위 호환성 문제가 생기지 않나요? Avro나 Protobuf를 사용하지 않은 이유는?

#### 📌 모범 답변
"맞습니다. JSON은 스키마 관리가 약해서 **호환성 문제**가 발생할 수 있습니다.

**현재 JSON 선택 이유:**
1. 학습용 프로젝트로 간단한 구조
2. 디버깅이 쉬움 (Kafka UI에서 바로 읽힘)
3. 초기 개발 속도 빠름

**운영 환경이라면 Avro 권장:**

**Avro 장점:**
1. **스키마 레지스트리**로 버전 관리
2. **Backward/Forward 호환성** 자동 검증
3. 바이너리 형식으로 용량 30% 절약

**Avro 스키마 예시:**
```json
{
  "type": "record",
  "name": "ReservationEvent",
  "fields": [
    {"name": "reservationId", "type": "long"},
    {"name": "userId", "type": "long"},
    {"name": "seatId", "type": "long"},
    {"name": "eventType", "type": "string"},
    {"name": "newField", "type": ["null", "string"], "default": null}
  ]
}
```

**JSON 마이그레이션 전략 (과도기):**
```java
// 버전 필드 추가
{
  "version": "v2",
  "reservationId": 123,
  "newField": "value"
}

// Consumer에서 버전별 처리
if ("v1".equals(event.getVersion())) {
    processV1(event);
} else if ("v2".equals(event.getVersion())) {
    processV2(event);
}
```

실무에서는 처음부터 Avro로 시작하거나, JSON Schema Validation을 적용하겠습니다."

#### 🔄 꼬리질문: "Protobuf vs Avro 중 어떤 걸 선택하시겠어요?"
**답변:**
**Protobuf:**
- Google 개발, gRPC와 궁합 좋음
- 코드 생성 기반 (타입 안정성 높음)
- 학습 곡선 낮음

**Avro:**
- Hadoop 에코시스템 표준
- 스키마 레지스트리 통합 우수 (Confluent Schema Registry)
- 동적 스키마 지원

**선택 기준:**
- Kafka 중심 아키텍처 → **Avro**
- MSA + gRPC → **Protobuf**

현재 프로젝트는 Kafka를 사용하므로 **Avro가 더 적합**합니다."

---

## 🟢 Category 4: 성능 & 최적화

### Q7. 현재 결제 시뮬레이션이 10초인데, 실제 외부 결제 API 호출 시 타임아웃이 발생하면 어떻게 처리해야 하나요? 사용자는 어떤 응답을 받게 되나요?

#### 📌 모범 답변
"외부 API 타임아웃은 **운영에서 가장 흔한 장애**입니다.

**1. Connection/Read Timeout 설정**
```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplateBuilder()
        .setConnectTimeout(Duration.ofSeconds(3))  // 연결 타임아웃
        .setReadTimeout(Duration.ofSeconds(10))    // 읽기 타임아웃
        .build();
}
```

**2. Circuit Breaker 적용 (Resilience4j)**
```java
@Bean
public CircuitBreaker paymentCircuitBreaker() {
    return CircuitBreaker.of("payment", CircuitBreakerConfig.custom()
        .failureRateThreshold(50)           // 실패율 50% 이상 시
        .waitDurationInOpenState(Duration.ofSeconds(30)) // 30초간 차단
        .slidingWindowSize(10)              // 최근 10개 요청 기준
        .build());
}

public boolean processPayment() {
    return circuitBreaker.executeSupplier(() -> {
        // 외부 결제 API 호출
        return paymentApiClient.charge();
    });
}
```

**사용자 응답:**
- Circuit CLOSED (정상): 결제 진행
- Circuit OPEN (장애): "결제 시스템 일시 장애입니다. 잠시 후 다시 시도해주세요."

**3. 비동기 처리로 전환**
```java
// 즉시 응답
return "결제 요청이 접수되었습니다. 결과는 알림으로 전송됩니다.";

@Async
public void processPaymentAsync(Long seatId, Long userId) {
    boolean success = paymentService.charge();

    if (success) {
        kafkaProducer.publish(ReservationEvent.success(...));
        emailService.sendConfirmation(userId);
    } else {
        seatCacheService.deleteSeatStatus(seatId);
        emailService.sendFailure(userId);
    }
}
```

**4. Fallback 전략**
- 1차: 메인 PG사 (토스페이먼츠)
- 실패 시: 보조 PG사 (NHN KCP)로 자동 전환

현재 프로젝트에서는 **2번(Circuit Breaker)** 적용이 가장 효과적입니다."

#### 🔄 꼬리질문 1: "Circuit Breaker가 OPEN 상태일 때, 사용자 요청을 어떻게 처리하나요?"
**답변:**
"세 가지 전략이 있습니다:

**1. Fail Fast (빠른 실패)**
```java
if (circuitBreaker.getState() == OPEN) {
    return "현재 결제 시스템 점검 중입니다.";
}
```

**2. Fallback 응답**
```java
.onFailure(event -> {
    // 대체 결제 수단 안내
    return "카드 결제 불가. 계좌이체로 진행하시겠습니까?";
})
```

**3. 대기열 등록**
```java
// Circuit OPEN 시 대기열에 추가
waitingPaymentQueue.add(new PaymentRequest(userId, seatId));
// Circuit 복구 시 자동 처리
```

사용자 경험을 위해 **명확한 오류 메시지 + 대안 제시**가 중요합니다."

#### 🔄 꼬리질문 2: "타임아웃 로그를 실시간으로 모니터링하려면?"
**답변:**
**Slack 알림 연동:**
```java
@Slf4j
public class PaymentService {

    private final SlackWebhook slackWebhook;

    public boolean processPayment() {
        try {
            return apiClient.charge();
        } catch (TimeoutException e) {
            log.error("결제 API 타임아웃: {}", e.getMessage());

            // 운영팀에 즉시 알림
            slackWebhook.send(
                "🚨 결제 API 타임아웃 발생\n" +
                "시간: " + LocalDateTime.now() +
                "\n에러: " + e.getMessage()
            );

            return false;
        }
    }
}
```

**Prometheus + Grafana:**
```java
// 타임아웃 카운터
meterRegistry.counter("payment.api.timeout").increment();

// 알림 규칙
alert: PaymentApiTimeout
expr: rate(payment_api_timeout_total[5m]) > 0.1
annotations:
  summary: "결제 API 타임아웃 다발"
```

운영에서는 **5분 내 3회 이상 타임아웃 시** 자동으로 Circuit OPEN하는 것이 안전합니다."

---

### Q8. 부하 테스트에서 동시 접속 1000명이 같은 좌석에 예약 요청을 보내면, 실제로 몇 명이나 성공할 것 같나요? 나머지는 어디서 걸러지나요?

#### 📌 모범 답변
"단계별로 필터링됩니다:

**1단계: 대기열 (WaitingQueueService)**
- Active User 한도: 100명 (예시)
- 통과: 100명
- 대기: 900명
- 응답: '현재 대기 중입니다. 순번: XX번'

**2단계: 분산 락 (Redisson)**
- 100명이 동시에 락 획득 시도
- 통과: 1명 (락 획득 성공)
- 실패: 99명 (1초 대기 후 타임아웃)
- 응답: 'FAIL: 접속자가 많아 처리 실패'

**3단계: Redis 선점 체크**
- 락 해제 후 다음 유저 진입
- 이미 SELECTED 상태 확인
- 응답: 'FAIL: 다른 사용자 결제 진행 중'

**4단계: 결제 시뮬레이션**
- 70% 성공률 가정
- 첫 유저도 30% 확률로 실패
- 실패 시 Redis 삭제 → 다음 유저에게 기회

**예상 결과:**
- 최종 성공: **1명**
- 대기열 대기: 900명
- 락 타임아웃: ~99명
- 선점 실패: 나머지

**시간 경과 후:**
QueueScheduler가 주기적으로 Active User를 보충하므로, 10분 후에는 대기자 중 일부가 성공합니다."

#### 🔄 꼬리질문 1: "부하 테스트는 어떤 도구로 하시겠어요?"
**답변:**
**1. JMeter (추천)**
```xml
<ThreadGroup>
  <numThreads>1000</numThreads>  <!-- 1000명 동시 -->
  <rampUp>1</rampUp>              <!-- 1초 안에 -->
  <HTTPSampler>
    <path>/reserve/69?userId=${userId}</path>
  </HTTPSampler>
</ThreadGroup>
```

**2. Gatling (Scala 기반)**
```scala
val scn = scenario("Ticket Reservation")
  .exec(http("reserve")
    .post("/reserve/69?userId=${userId}"))

setUp(
  scn.inject(atOnceUsers(1000))
).protocols(httpProtocol)
```

**3. 프로젝트 내 ReservationLoadTest.java 활용**
- 이미 구현된 테스트 코드 실행
- 결과를 README에 문서화

**측정 지표:**
- TPS (Transaction Per Second)
- 평균/최대 응답 시간
- 성공률 (%)
- 에러 타입별 분포"

#### 🔄 꼬리질문 2: "1000명이 서로 다른 좌석을 예약하면 성공률이 어떻게 달라지나요?"
**답변:**
"극적으로 향상됩니다.

**같은 좌석 (현재 시나리오):**
- 성공: 1명
- 성공률: 0.1%

**서로 다른 좌석:**
- 각 좌석마다 독립적인 락
- Active User 100명 전원 성공 가능
- 성공률: 10% × 70%(결제) = **7%**

**계산:**
```
동시 처리량 = min(Active User 한도, 서버 처리 용량)
            = min(100명, 8코어 × 20 TPS) = 100명

성공률 = 100명 / 1000명 × 결제 성공률(70%) = 7%
```

**운영 최적화:**
- 서버 인스턴스 3대로 늘리면: 100 × 3 = 300명 처리
- 성공률: 30% × 70% = **21%**

이것이 부하 테스트가 중요한 이유입니다."

---

## 🔵 Category 5: 트러블슈팅 & 운영

### Q9. 운영 중에 Redis 메모리가 계속 늘어나서 OOM(Out of Memory)이 발생했습니다. 어떤 순서로 원인을 찾고 해결하시겠어요?

#### 📌 모범 답변
"Redis OOM은 운영에서 자주 발생하는 문제입니다. 체계적으로 접근:

**1. 현황 파악 (5분)**
```bash
# 메모리 사용량 확인
redis-cli INFO memory
# used_memory_human: 980MB
# maxmemory: 1GB

# 가장 큰 키 찾기
redis-cli --bigkeys
# Biggest set   found: 'ticket:waiting:queue' (500,000 members)
# Biggest string found: 'state:seat:*' (50,000 keys)

# 키 개수 확인
redis-cli DBSIZE
# 120,000 keys

# TTL 없는 키 찾기
redis-cli KEYS * | while read key; do
  ttl=$(redis-cli TTL "$key")
  if [ "$ttl" -eq "-1" ]; then
    echo "$key: 영구 보관"
  fi
done
```

**2. 원인 분석**
프로젝트에서 가능한 원인:

| 키 패턴 | 예상 원인 | 확인 명령어 |
|---------|----------|------------|
| `ticket:waiting:queue` | 대기열 무한 증가 | `ZCARD ticket:waiting:queue` |
| `ticket:active:users` | Active User 미정리 | `SCARD ticket:active:users` |
| `state:seat:*` | TTL 누락 | `TTL state:seat:69` |
| `lock:seat:*` | 락 미해제 | `KEYS lock:seat:*` |

**3. 즉시 조치 (긴급)**
```bash
# Eviction Policy 변경 (일시적)
redis-cli CONFIG SET maxmemory-policy allkeys-lru

# 불필요한 키 삭제
redis-cli EVAL "return redis.call('del', unpack(redis.call('keys', 'lock:seat:*')))" 0

# 메모리 확보 확인
redis-cli INFO memory
```

**4. 근본 해결**
```java
// 1) 모든 키에 적절한 TTL 설정
seatCacheService.updateSeatStatus(seatId, "SELECTED", 5); // ✅

// 2) Scheduler로 주기적 정리
@Scheduled(fixedRate = 600000)
public void cleanupExpiredData() {
    // 10분 이상 된 대기열 제거
    long cutoff = System.currentTimeMillis() - 600000;
    redisTemplate.opsForZSet().removeRangeByScore(
        "ticket:waiting:queue", 0, cutoff);

    // Active User 타임아웃 체크
    waitingQueueService.removeInactiveUsers();
}

// 3) Active User를 Hash + TTL로 변경
redisTemplate.opsForHash().put("active:users", userId, timestamp);
redisTemplate.expire("active:users:" + userId, 5, TimeUnit.MINUTES);
```

**5. 모니터링 설정**
```yaml
# Prometheus Alert
- alert: RedisHighMemory
  expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.8
  for: 5m
  annotations:
    summary: "Redis 메모리 80% 초과"
```

이 프로젝트에서는 **Active User Set**이 원인일 가능성이 가장 높습니다."

#### 🔄 꼬리질문: "Redis Cluster로 확장하면 메모리 문제가 해결되나요?"
**답변:**
"메모리를 분산할 수 있지만, 근본 원인 해결은 아닙니다.

**Redis Cluster 특징:**
- 16,384개 슬롯으로 키 분산
- 메모리 용량 = 단일 노드 × 노드 수
- 예: 1GB × 3 노드 = 3GB

**하지만:**
- 메모리 누수는 계속 발생
- 운영 복잡도 증가
- 샤딩 키 설계 필요

**올바른 접근:**
1. 먼저 메모리 누수 원인 제거 (TTL, Scheduler)
2. 그래도 부족하면 Cluster 검토
3. 또는 Redis Enterprise (자동 메모리 관리)

**Cluster 구성 예시:**
```java
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useClusterServers()
        .addNodeAddress("redis://127.0.0.1:7000")
        .addNodeAddress("redis://127.0.0.1:7001")
        .addNodeAddress("redis://127.0.0.1:7002");
    return Redisson.create(config);
}
```

운영에서는 **단일 인스턴스 최적화 → Sentinel (HA) → Cluster (확장)** 순서로 진행합니다."

---

### Q10. 새벽 2시에 '예약이 안 돼요'라는 고객 문의가 들어왔습니다. 어떤 순서로 디버깅하시겠어요? (DB는 정상, 서버도 떠있음)

#### 📌 모범 답변
"야간 장애 대응 시나리오입니다. 침착하게 단계별 확인:

**1. 로그 확인 (1분)**
```bash
# 최근 에러 로그
tail -f /var/log/ticket-app/application.log | grep ERROR

# 특정 사용자 검색
grep "userId=12345" application.log | tail -20

# 특정 좌석 검색
grep "seatId=69" application.log | tail -20
```

**2. 외부 의존성 체크 (2분)**
```bash
# Redis 연결 확인
redis-cli PING
# PONG 응답 확인

# Kafka 상태 확인
docker ps | grep kafka
# 컨테이너 실행 중인지 확인

# MySQL 연결 확인
mysql -u root -p -e "SELECT 1"
# Query OK 확인
```

**3. 비즈니스 로직 확인 (3분)**
```bash
# Redis에서 좌석 상태 확인
redis-cli GET "state:seat:69"
# "CONFIRMED" → 이미 판매 완료
# "SELECTED" → 다른 사용자 결제 중
# (null) → 정상

# DB에서 실제 상태 확인
mysql> SELECT id, seat_number, status FROM seat WHERE id = 69;
# status가 AVAILABLE인지 확인

# 정합성 불일치 발견 시
redis-cli DEL "state:seat:69"  # Redis 캐시 삭제
```

**4. 분산 락 확인 (2분)**
```bash
# 락이 안 풀리고 있는지 확인
redis-cli KEYS "lock:seat:*"
# 결과가 많으면 락 미해제 문제

# 특정 락 TTL 확인
redis-cli TTL "lock:seat:69"
# -1이면 영구 락 (문제!)
# 0~2면 정상

# 강제 해제 (신중하게!)
redis-cli DEL "lock:seat:69"
```

**5. 대기열 확인 (2분)**
```bash
# 사용자가 Active User인지 확인
redis-cli SISMEMBER "ticket:active:users" "12345"
# 0 → 대기열에 있음
# 1 → Active 상태

# 대기 순번 확인
redis-cli ZRANK "ticket:waiting:queue" "12345"
# 100 → 101번째 대기 중

# Active User 수 확인
redis-cli SCARD "ticket:active:users"
# 100명 → 한도 도달
```

**6. 고객 응대**
```
# 원인 파악 전 (1분 이내)
"확인 중입니다. 5분 내 회신드리겠습니다."

# Redis 캐시 불일치 발견 시
"일시적 장애로 복구했습니다. 다시 시도해주세요."

# 대기열 대기 중
"현재 101번째 대기 중이십니다. 약 5분 후 자동 입장됩니다."

# 이미 판매 완료
"해당 좌석은 이미 예약 완료되었습니다. 다른 좌석을 선택해주세요."
```

**7. 사후 조치**
```java
// 장애 보고서 작성
- 발생 시간: 2024-01-29 02:15
- 원인: Redis-DB 정합성 불일치
- 영향 범위: 좌석 ID 69번 1건
- 조치 사항: Redis 캐시 삭제
- 재발 방지: 정합성 검증 Scheduler 추가

// 모니터링 강화
@Scheduled(cron = "0 */5 * * * *") // 5분마다
public void healthCheck() {
    // DB와 Redis 상태 비교
    // 불일치 발견 시 Slack 알림
}
```

경험상 **Redis 상태 불일치**나 **락 미해제**가 가장 흔한 원인입니다."

#### 🔄 꼬리질문 1: "Actuator로 빠르게 상태 확인하는 방법은?"
**답변:**
```bash
# Health Check (한 번에 모든 의존성 확인)
curl http://localhost:8080/actuator/health

# 응답 예시
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "DOWN", "error": "Connection refused"},
    "kafka": {"status": "UP"}
  }
}
```

**Custom Health Indicator 추가:**
```java
@Component
public class ReservationHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, String> redisTemplate;
    private final SeatRepository seatRepository;

    @Override
    public Health health() {
        try {
            // Redis 연결 확인
            redisTemplate.opsForValue().get("health-check");

            // DB 연결 확인
            seatRepository.count();

            // 대기열 상태 확인
            Long queueSize = redisTemplate.opsForZSet()
                .size("ticket:waiting:queue");

            return Health.up()
                .withDetail("queueSize", queueSize)
                .build();

        } catch (Exception e) {
            return Health.down()
                .withException(e)
                .build();
        }
    }
}
```

야간에는 `/actuator/health` 한 번으로 전체 상태를 파악할 수 있습니다."

#### 🔄 꼬리질문 2: "새벽 시간에 자동으로 복구하는 방법은?"
**답변:**
**1. Self-Healing Scheduler**
```java
@Scheduled(cron = "0 */10 * * * *") // 10분마다
public void autoRecovery() {
    try {
        // 1) 장애 감지
        if (!isRedisHealthy()) {
            log.error("Redis 장애 감지");
            slackWebhook.send("🚨 Redis 장애 발생");
            return;
        }

        // 2) 정합성 복구
        List<Seat> seats = seatRepository.findAll();
        for (Seat seat : seats) {
            String redisStatus = seatCacheService.getSeatStatus(seat.getId());
            if (!redisStatus.equals(seat.getStatus().name())) {
                // Redis를 DB 기준으로 동기화
                seatCacheService.updateSeatStatus(
                    seat.getId(), seat.getStatus().name(), 0);
                log.info("정합성 복구: seatId={}", seat.getId());
            }
        }

        // 3) 미해제 락 정리
        Set<String> lockKeys = redisTemplate.keys("lock:seat:*");
        for (String key : lockKeys) {
            Long ttl = redisTemplate.getExpire(key);
            if (ttl == -1) { // 영구 락
                redisTemplate.delete(key);
                log.warn("미해제 락 삭제: {}", key);
            }
        }

    } catch (Exception e) {
        log.error("자동 복구 실패", e);
    }
}
```

**2. Circuit Breaker + Retry**
```java
@Retryable(
    value = {RedisConnectionException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000)
)
public String getSeatStatus(Long seatId) {
    return redisTemplate.opsForValue().get("state:seat:" + seatId);
}

@Recover
public String recoverGetSeatStatus(RedisConnectionException e, Long seatId) {
    log.warn("Redis 장애, DB Fallback: seatId={}", seatId);
    return seatRepository.findById(seatId)
        .map(seat -> seat.getStatus().name())
        .orElse("AVAILABLE");
}
```

운영에서는 **자동 복구 + 알림**을 조합해서, 장애를 스스로 해결하되 담당자에게도 알립니다."

---

## 📚 면접 직전 체크리스트

### ✅ 반드시 암기할 것
- [ ] 락 타임아웃 산정 공식 (Q1)
- [ ] 조기 락 해제의 이유와 안전성 (Q2)
- [ ] Active User 메모리 누수 해결 3가지 (Q3)
- [ ] Kafka Offset 동작 원리 (Q5)
- [ ] Circuit Breaker 3단계 상태 (Q7)
- [ ] 부하 테스트 단계별 필터링 (Q8)
- [ ] Redis OOM 디버깅 5단계 (Q9)
- [ ] 야간 장애 대응 순서 (Q10)

### ✅ 실습해볼 것
- [ ] Redis 메모리 확인: `redis-cli INFO memory`
- [ ] 대기열 조회: `redis-cli ZCARD ticket:waiting:queue`
- [ ] 락 확인: `redis-cli KEYS "lock:seat:*"`
- [ ] Health Check: `curl localhost:8080/actuator/health`

### ✅ 수치 암기
- [ ] 결제 성공률: 70%
- [ ] 락 대기 시간: 1초 (운영은 1.5~3초 권장)
- [ ] Redis TTL: 5분
- [ ] Active User 한도: 100명 (예시)
- [ ] Circuit Breaker 실패율: 50%

### ✅ 키워드 암기
- **동시성**: 분산 락, 조기 해제, Optimistic Lock
- **대기열**: SortedSet, Active User, TTL
- **Kafka**: Offset, At-least-once, 멱등성
- **장애 처리**: Circuit Breaker, Fallback, Self-Healing
- **모니터링**: Prometheus, Actuator, Slack 알림

---

**면접 전날 이 문서를 3번 읽으세요!** 🔥
