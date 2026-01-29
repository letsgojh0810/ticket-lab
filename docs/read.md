# 기술면접 핵심개념 요약본 - 암기용

> 면접 1시간 전에 읽으세요! 📖

---

## 🔐 1. 분산 락 (Redisson)

### 핵심 개념
| 항목 | 설명 |
|------|------|
| **목적** | 다중 서버 환경에서 동시성 제어 |
| **방식** | Redis SETNX + TTL (Redisson이 자동 관리) |
| **대기 시간** | 1초 (운영: 1.5~3초 권장) |
| **점유 시간** | 2초 (실측값의 2배 여유) |
| **Watch Dog** | 점유 시간 자동 연장 (30초마다 갱신) |

### 타임아웃 산정 공식
```
대기 시간 = Redis 조회(5ms) + DB 조회(20ms) + 여유(100ms) = 최소 150ms
점유 시간 = 작업 시간 실측 × 2배
```

### 주요 API
```java
RLock lock = redissonClient.getLock("lock:seat:" + seatId);
boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
if (lock.isHeldByCurrentThread()) {
    lock.unlock();
}
```

### vs Optimistic Lock
| 구분 | Redisson 분산 락 | Optimistic Lock |
|------|-----------------|-----------------|
| 방식 | 큐 방식 순차 처리 | 충돌 감지 후 재시도 |
| DB 부하 | 낮음 | 높음 (재시도 폭풍) |
| 응답 속도 | 빠름 (즉시 실패) | 느림 (재시도 반복) |
| 적용 | 고경합 상황 (티켓팅) | 저경합 상황 |

---

## 💾 2. Redis 캐싱 전략

### 3단 상태 관리
```
AVAILABLE (초기) → SELECTED (선점, 5분 TTL) → CONFIRMED (확정, 영구)
```

### Cache-Aside Pattern (현재 구조)
1. 요청 → Redis 조회
2. Cache Miss → DB 조회
3. DB 데이터를 Redis에 저장
4. 응답

### 정합성 보장
| 문제 | 해결책 |
|------|--------|
| Redis 업데이트 성공, DB 롤백 | TTL 5분으로 자동 복구 |
| DB 커밋 성공, Redis 실패 | DB를 Source of Truth로 간주, Fallback |
| 불일치 지속 | Scheduler로 10분마다 검증 |

### 주요 명령어
```bash
# 상태 조회
redis-cli GET "state:seat:69"

# TTL 확인
redis-cli TTL "state:seat:69"

# 강제 삭제
redis-cli DEL "state:seat:69"
```

---

## 🎫 3. 대기열 시스템

### 구조
```
SortedSet (대기열) + Set (Active Users)
```

| 자료구조 | 키 | 용도 | 정렬 기준 |
|---------|-----|------|----------|
| **SortedSet** | `ticket:waiting:queue` | 대기 중인 유저 | 타임스탬프 (FIFO) |
| **Set** | `ticket:active:users` | 입장 허가된 유저 | 없음 |

### 플로우
```
1. registerAndGetRank(userId) → SortedSet에 추가
2. isAllowed(userId) → Set에 있는지 확인
3. QueueScheduler → 주기적으로 대기열에서 Active로 이동
4. removeActiveUser(userId) → 결제 완료 시 Set에서 제거
```

### 메모리 누수 방지
| 방법 | 구현 | 효과 |
|------|------|------|
| **TTL** | Hash + Field별 5분 만료 | 자동 정리 |
| **Heartbeat** | 30초마다 갱신 요청 | 연결 끊김 감지 |
| **Scheduler** | 10분마다 오래된 유저 삭제 | 강제 정리 |

### 주요 명령어
```bash
# 대기 인원 확인
redis-cli ZCARD "ticket:waiting:queue"

# Active 유저 수 확인
redis-cli SCARD "ticket:active:users"

# 순번 확인
redis-cli ZRANK "ticket:waiting:queue" "12345"
```

---

## 📨 4. Kafka 이벤트 기반 아키텍처

### 핵심 개념
| 항목 | 설정 |
|------|------|
| **Topic** | `reservation-events` |
| **Partitions** | 3개 (확장 가능) |
| **Consumer Group** | `ticket-reservation-group` |
| **Commit Mode** | `MANUAL_IMMEDIATE` (수동 커밋) |
| **acks** | `all` (모든 복제본 확인) |
| **Retention** | 7일 |

### 이벤트 타입
```java
RESERVATION_SUCCESS  → 예약 성공 (이메일, 알림톡)
RESERVATION_FAILED   → 예약 실패 (로그, 통계)
RESERVATION_CANCELLED → 예약 취소 (환불, 좌석 복구)
```

### Offset 관리
| 상황 | 동작 |
|------|------|
| **정상 처리** | 수동 커밋 → Offset 증가 |
| **처리 실패** | 커밋 안 함 → 재시작 시 재처리 |
| **Consumer 재시작** | 마지막 커밋된 Offset부터 |
| **새 Consumer** | `earliest` 설정으로 처음부터 |

### At-least-once vs Exactly-once
| 방식 | 보장 | 중복 가능성 | 해결책 |
|------|------|-------------|--------|
| **At-least-once** | 메시지 유실 없음 | 있음 | 멱등성 구현 |
| **Exactly-once** | 중복 없음 | 없음 | Kafka Transactional API (복잡) |

### 멱등성 구현
```java
// 이벤트 ID로 중복 체크
if (processedEventRepository.existsByEventId(event.getId())) {
    return; // 이미 처리됨
}

// 비즈니스 로직 처리
sendEmail(event);

// 처리 완료 기록
processedEventRepository.save(event.getId());
```

---

## ⚡ 5. 동시성 제어 전략

### 조기 락 해제 (Early Lock Release)
```
[기존] Lock → Redis Check → DB Commit → Unlock (2초 점유)
[개선] Lock → Redis Select → Unlock → Payment → DB Commit (0.1초 점유)
```

**효과:** 동시 처리량 20배 증가

### 필터링 단계 (1000명 → 1명)
```
1단계: 대기열      → 900명 대기
2단계: 분산 락     → 99명 타임아웃
3단계: Redis 선점  → 나머지 선점 실패
4단계: 결제        → 1명 성공 (70% 확률)
```

### Race Condition 방지
| 구간 | 위험 | 해결책 |
|------|------|--------|
| 67~69행 | Redis 업데이트 도중 장애 | Lua 스크립트 (원자성) |
| 78행 | 결제 중 타임아웃 | Circuit Breaker |
| 84행 | DB 커밋 실패 | @Transactional 롤백 |

---

## 🛡️ 6. 장애 처리

### Redis 장애
```java
try {
    return redisTemplate.opsForValue().get(key);
} catch (Exception e) {
    log.error("Redis 장애, DB Fallback");
    return seatRepository.findById(seatId).orElse("AVAILABLE");
}
```

**전략:** Graceful Degradation (우아한 성능 저하)

### Kafka 장애
```java
kafkaTemplate.send(topic, message)
    .whenComplete((result, ex) -> {
        if (ex != null) {
            log.error("Kafka 발행 실패 (예약은 정상)");
            // 알림만 누락, 비즈니스는 성공
        }
    });
```

**전략:** 핵심 기능 보호 (예약 > 알림)

### Circuit Breaker (Resilience4j)
| 상태 | 조건 | 동작 |
|------|------|------|
| **CLOSED** | 정상 | 요청 통과 |
| **OPEN** | 실패율 50% 이상 | 즉시 실패 (30초) |
| **HALF_OPEN** | 30초 후 | 테스트 요청 5개 |

```java
CircuitBreakerConfig.custom()
    .failureRateThreshold(50)           // 50% 이상 실패 시
    .waitDurationInOpenState(30초)      // OPEN 유지 시간
    .slidingWindowSize(10)              // 최근 10개 기준
    .build();
```

---

## 🚀 7. 성능 최적화

### 부하 테스트 지표
| 지표 | 목표 | 측정 도구 |
|------|------|----------|
| **TPS** | 100 이상 | JMeter, Gatling |
| **응답 시간** | P95 < 2초 | JMeter Response Time Graph |
| **성공률** | 95% 이상 | JMeter Summary Report |
| **동시 접속** | 1000명 | Thread Group 설정 |

### JMeter 설정 예시
```xml
<ThreadGroup>
  <numThreads>1000</numThreads>
  <rampUp>10</rampUp>  <!-- 10초에 걸쳐 1000명 증가 -->
  <loopCount>1</loopCount>
</ThreadGroup>
```

### N+1 문제 방지
```java
// ❌ N+1 발생
List<Reservation> reservations = reservationRepository.findAll();
for (Reservation r : reservations) {
    r.getSeat().getSeatNumber(); // 매번 쿼리
}

// ✅ Fetch Join
@Query("SELECT r FROM Reservation r JOIN FETCH r.seat")
List<Reservation> findAllWithSeat();
```

---

## 📊 8. 모니터링

### Actuator Health Check
```bash
curl http://localhost:8080/actuator/health

# 응답
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "DOWN"},  # ⚠️ 장애
    "kafka": {"status": "UP"}
  }
}
```

### Prometheus 메트릭
```java
// 타임아웃 카운터
meterRegistry.counter("reservation.lock.timeout").increment();

// 응답 시간 측정
Timer.Sample sample = Timer.start(meterRegistry);
// 작업 수행
sample.stop(meterRegistry.timer("reservation.duration"));
```

### Slack 알림
```java
if (타임아웃 발생) {
    slackWebhook.send("🚨 결제 API 타임아웃 발생\n시간: " + now);
}
```

### 알림 기준
| 지표 | 임계값 | 알림 |
|------|--------|------|
| Redis 메모리 | 80% | 경고 |
| Kafka Lag | 1000개 | 경고 |
| 락 타임아웃 | 5분 내 3회 | 긴급 |
| API 응답 시간 | P95 > 3초 | 경고 |

---

## 🔧 9. 주요 명령어 치트시트

### Redis
```bash
# 메모리 확인
redis-cli INFO memory

# 큰 키 찾기
redis-cli --bigkeys

# 키 개수
redis-cli DBSIZE

# TTL 확인
redis-cli TTL "state:seat:69"

# 패턴 검색
redis-cli KEYS "lock:seat:*"

# 대기열 크기
redis-cli ZCARD "ticket:waiting:queue"

# Active User 수
redis-cli SCARD "ticket:active:users"

# Eviction Policy 변경
redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

### MySQL
```bash
# 연결 확인
mysql -u root -p -e "SELECT 1"

# 좌석 상태 조회
mysql> SELECT id, seat_number, status FROM seat WHERE id = 69;

# 인덱스 확인
mysql> SHOW INDEX FROM seat;
```

### Kafka
```bash
# Topic 리스트
kafka-topics --list --bootstrap-server localhost:9092

# Consumer Lag 확인
kafka-consumer-groups --describe --group ticket-reservation-group \
  --bootstrap-server localhost:9092

# 메시지 조회 (최근 10개)
kafka-console-consumer --topic reservation-events \
  --from-beginning --max-messages 10 \
  --bootstrap-server localhost:9092
```

### Docker
```bash
# 컨테이너 상태 확인
docker ps

# 로그 확인
docker logs kafka -f --tail 100

# 재시작
docker-compose restart redis
```

---

## 📐 10. 면접 필수 수치 암기

### 타임아웃 설정
```
분산 락 대기: 1초 (운영: 1.5~3초)
분산 락 점유: 2초 (실측값 × 2)
Redis TTL: 5분 (선점 상태)
결제 시뮬레이션: 10초
외부 API 연결: 3초
외부 API 읽기: 10초
```

### 용량 설정
```
Active User 한도: 100명
Redis 최대 메모리: 1GB (운영: 4GB)
DB 커넥션 풀: 10개 (HikariCP 기본)
Kafka 파티션: 3개
Consumer Group: 1개
```

### 성능 지표
```
결제 성공률: 70% (랜덤)
목표 TPS: 100 이상
목표 응답 시간: P95 < 2초
목표 성공률: 95% 이상
```

### 확률 계산
```
1000명 동시 접속 (같은 좌석)
→ Active User 100명 통과
→ 분산 락 1명 획득
→ 결제 성공 70%
→ 최종 성공: 1명 × 0.7 = 0.7명

1000명 동시 접속 (서로 다른 좌석)
→ Active User 100명 통과
→ 모두 성공 가능
→ 성공률: 10% × 70% = 7%
```

---

## 🎯 11. 트러블슈팅 플로우

### Redis OOM
```
1. INFO memory → 사용량 확인
2. --bigkeys → 큰 키 찾기
3. KEYS * → TTL 없는 키 찾기
4. CONFIG SET maxmemory-policy allkeys-lru
5. Scheduler로 정리
```

### 예약 실패 장애
```
1. 로그 확인 (tail -f application.log | grep ERROR)
2. Redis PING (연결 확인)
3. GET "state:seat:X" (상태 확인)
4. MySQL SELECT (DB 확인)
5. KEYS "lock:seat:*" (락 확인)
6. SISMEMBER "ticket:active:users" (대기열 확인)
```

### Kafka Lag 증가
```
1. Consumer 상태 확인 (docker ps | grep kafka)
2. Lag 측정 (kafka-consumer-groups --describe)
3. Consumer 스케일 아웃 (인스턴스 추가)
4. 파티션 증가 고려
```

---

## 🗣️ 12. 면접 답변 템플릿

### "왜 이렇게 설계했나요?"
```
1. 문제 정의: "티켓팅은 동시 접속이 많아서..."
2. 대안 비교: "Optimistic Lock도 고려했지만..."
3. 선택 이유: "분산 락이 DB 부하를 줄여서..."
4. 트레이드오프: "다만 Redis 의존성이 생겨서..."
5. 보완책: "장애 시 DB Fallback으로..."
```

### "운영 환경이라면?"
```
1. 현재 방식: "학습용으로 단순하게..."
2. 한계점: "하지만 XXX 문제가 있어서..."
3. 개선 방안: "운영에서는 YYY를 추가..."
4. 실제 사례: "비슷한 케이스로 ZZZ..."
```

### "장애가 발생하면?"
```
1. 즉시 조치: "먼저 로그를 확인하고..."
2. 임시 복구: "일단 Redis 캐시를 삭제..."
3. 근본 원인: "원인은 TTL 미설정..."
4. 재발 방지: "Scheduler로 자동 정리..."
5. 모니터링: "Prometheus 알림 추가..."
```

---

## ⏰ 13. 면접 1시간 전 체크리스트

### 암기 확인
- [ ] 분산 락 타임아웃 (1초 대기, 2초 점유)
- [ ] Redis 3단 상태 (AVAILABLE → SELECTED → CONFIRMED)
- [ ] Kafka Offset 동작 (수동 커밋, At-least-once)
- [ ] Circuit Breaker 상태 (CLOSED → OPEN → HALF_OPEN)
- [ ] 부하 테스트 필터링 (1000 → 100 → 1 → 0.7)

### 명령어 확인
- [ ] `redis-cli INFO memory`
- [ ] `redis-cli ZCARD ticket:waiting:queue`
- [ ] `curl localhost:8080/actuator/health`
- [ ] `kafka-consumer-groups --describe`

### 핵심 개념 확인
- [ ] 조기 락 해제의 이유 (동시 처리량 20배)
- [ ] 멱등성 구현 방법 (Event ID 체크)
- [ ] Redis-DB 정합성 보장 (DB를 Source of Truth)
- [ ] Active User 메모리 누수 해결 (TTL + Scheduler)

---

## 💡 14. 면접장에서 자주 하는 실수

### ❌ 피해야 할 답변
```
"잘 모르겠습니다" → "학습 중이며, 제 생각으로는..."
"적당히 정했어요" → "부하 테스트 기반으로 조정..."
"무조건 이게 좋습니다" → "XXX는 장점이지만, YYY는 단점..."
"이론적으로는..." → "실제로 테스트해보니..."
```

### ✅ 좋은 답변 패턴
```
1. 결론 먼저: "분산 락을 사용했습니다."
2. 이유 설명: "왜냐하면 DB 부하를 줄이기 위해..."
3. 대안 비교: "Optimistic Lock도 고려했지만..."
4. 한계 인정: "다만 Redis 장애 시..."
5. 보완책 제시: "DB Fallback으로 해결..."
```

---

## 🔥 15. 마지막 당부

### 면접관이 보는 것
- ❌ 모든 걸 다 아는가?
- ✅ **문제를 어떻게 접근하는가?**
- ✅ **트레이드오프를 이해하는가?**
- ✅ **운영 관점으로 생각하는가?**

### 자신감 있게 말하기
```
"확실하지 않지만..." ❌
"제가 이해한 바로는..." ✅

"아마도..." ❌
"부하 테스트 결과..." ✅

"그냥..." ❌
"이런 이유로..." ✅
```

### 모르는 질문이 나오면
```
1. 솔직하게: "해당 부분은 학습 중입니다."
2. 연결하기: "다만 비슷한 XX는 이렇게..."
3. 배울 의지: "면접 후 꼭 공부해보겠습니다."
```

---

## 🎓 최종 점검

### 반드시 말할 것
- "부하 테스트로 검증했습니다"
- "트레이드오프를 고려했습니다"
- "운영 환경에서는 XXX를 추가하겠습니다"
- "장애 시 YYY로 복구합니다"

### 절대 말하지 말 것
- "시간이 없어서 못 했어요"
- "그냥 적당히 정했어요"
- "이론적으로만 알아요"
- "실제로 해본 적은 없어요"

---

**이 문서를 프린트해서 면접장 가기 전에 3번 읽으세요!** 📄

**화이팅! 🚀**
