# 부하 테스트 시나리오 및 트러블슈팅

## 테스트 시나리오

### 목표
- 1000명의 유저가 100개 좌석을 두고 경쟁
- 결제 성공률 80% (20%는 이탈/실패)
- **최종 결과: 100개 좌석 전부 예약 성공**

### 시스템 구성
| 항목 | 값 |
|------|-----|
| 총 좌석 수 | 100개 |
| 총 유저 수 | 1000명 |
| Max Active Users | 200명 |
| 결제 성공률 | 80% |
| 결제 소요 시간 | 10초 |
| Active User TTL | 5분 |

### 예상 결과
| 메트릭 | 예상 값 |
|--------|---------|
| reservation_success | **100** (전 좌석 매진) |
| reservation_failed | **900** (매진으로 인한 실패) |
| payment_success | ~125 (결제 시도 중 80% 성공) |
| payment_failed | ~25 (결제 시도 중 20% 실패) |

---

## 트러블슈팅 기록

### 문제 1: 270명만 예약 시도 (730명 누락)

#### 증상
```
📊 최종 메트릭:
reservation_success_total: 79
reservation_failed_total: 191
총 처리: 270명 (1000명 중 730명 누락!)
```

#### 원인 분석

**잘못된 스크립트 로직:**
```bash
# Round 1: 유저 1~100 예약 요청
for i in $(seq 1 100); do
  userId=$i
  curl -X POST "/reserve" -d '{"userId": $userId, ...}'
done

# Round 2: 유저 101~200 예약 요청
for i in $(seq 101 200); do
  ...
done
```

**문제점:**
1. 스크립트는 유저 번호 순서(1~100, 101~200...)로 요청을 보냄
2. 하지만 Active 유저는 **대기열 진입 순서**로 100명씩만 전환됨
3. 대기열 진입 순서 ≠ 유저 번호 순서 (병렬 진입이라 순서 뒤섞임)

```
예시:
- 대기열 진입 순서: 7, 23, 1, 45, 89, 12, ...
- 스케줄러가 Active로 전환: 7, 23, 1, 45, ... (상위 100명)
- 스크립트 요청: userId=1, 2, 3, 4, 5, ...

→ userId=2, 3, 4, 5는 Active가 아니라서 403 에러!
```

#### 해결
각 유저가 **자신이 Active가 될 때까지 대기 후 예약**하도록 스크립트 수정:

```bash
process_user() {
  local userId=$1

  # 1. 대기열 진입
  curl -X POST "/queue/enter?userId=${userId}"

  # 2. Active 될 때까지 대기 (polling)
  while true; do
    status=$(curl "/queue/status?userId=${userId}")
    if [ "$status" = "READY" ]; then
      break
    fi
    sleep 1
  done

  # 3. 이제 예약 시도
  curl -X POST "/reserve" -d '{"userId": $userId, ...}'
}
```

---

### 문제 2: 100개 좌석 중 77개만 판매

#### 증상
```
📊 최종 메트릭:
reservation_success_total: 77
reservation_failed_total: 922
payment_failed_total: 35

→ 23개 좌석이 미판매!
```

#### 원인 분석

**잘못된 좌석 할당 로직:**
```bash
for userId in $(seq 1 1000); do
  seatId=$(( (userId - 1) % 100 + 1 ))  # 유저마다 고정된 좌석
  curl -X POST "/reserve" -d '{"seatId": $seatId, ...}'
done
```

**문제점:**
```
User 1, 101, 201, 301, ... → Seat 1 시도
User 2, 102, 202, 302, ... → Seat 2 시도
...
```

1. User 1이 Seat 1 결제 실패 → Seat 1이 다시 풀림
2. User 101이 Seat 1 시도하지만, 이미 "이미 예약된 좌석" 에러를 받은 후
3. **재시도 로직이 없어서** 풀린 Seat 1을 아무도 안 가져감
4. 결국 결제 실패한 23개 좌석은 미판매로 남음

#### 해결
**실패하면 다른 좌석 재시도**하도록 수정:

```bash
process_user() {
  local userId=$1

  # ... 대기열 진입 및 Active 대기 ...

  # 모든 좌석 순회하며 시도 (성공할 때까지)
  for seatId in $(seq 1 100); do
    result=$(curl -X POST "/reserve" -d '{"seatId": $seatId, ...}')

    if [[ "$result" == *"SUCCESS"* ]]; then
      echo "✅ User $userId -> Seat $seatId"
      return 0  # 성공하면 종료
    fi
    # 실패하면 다음 좌석 시도
  done

  echo "❌ User $userId: 모든 좌석 매진"
}
```

---

### 문제 3: "존재하지 않는 좌석" 에러

#### 증상
```
User 973 -> Seat 73: ERROR: 존재하지 않는 좌석입니다.
User 974 -> Seat 74: ERROR: 존재하지 않는 좌석입니다.
...
reservation_success_total: 0
```

#### 원인 분석
```sql
-- 테이블 초기화 시
DELETE FROM seat;  -- 데이터만 삭제, auto_increment 유지

-- 앱 재시작 후 DataInitializer 실행
INSERT INTO seat (seat_number) VALUES ('1번 좌석');
-- → id = 101 (이전 데이터 때문에 auto_increment가 101부터 시작)
```

스크립트는 `seatId=1~100`으로 요청하지만, 실제 DB의 seat.id는 `101~200`

#### 해결
```sql
-- TRUNCATE로 auto_increment까지 리셋
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE reservation;
TRUNCATE TABLE seat;
SET FOREIGN_KEY_CHECKS = 1;
```

---

## 최종 성공 결과

```
==============================================
📊 최종 결과
==============================================
reservation_success_total: 100
reservation_failed_total:  900

예약 완료 좌석: 100
```

### 성공 요인
1. **각 유저가 Active 될 때까지 대기** → 모든 유저가 예약 시도 가능
2. **실패 시 다른 좌석 재시도** → 결제 실패한 좌석도 결국 판매됨
3. **DB 완전 초기화** → seat.id가 1부터 시작

---

## 모니터링 메트릭 해석

| 메트릭 | 의미 |
|--------|------|
| `reservation_success_total` | 예약 확정 수 (= 판매된 좌석 수) |
| `reservation_failed_total` | 예약 실패 수 (매진 + 결제 실패) |
| `payment_success_total` | 결제 성공 수 |
| `payment_failed_total` | 결제 실패 수 (~20%) |
| `lock_timeout_total` | 락 획득 실패 수 (동시 접속 과다 시 증가) |
| `queue_waiting_size` | 현재 대기열 인원 |
| `reservation_active_count` | 현재 예약 진행 중인 유저 수 |

### Grafana 대시보드 확인 포인트
1. **Reservation Rate 그래프**: 초반 급증 → 매진 후 실패만 발생
2. **Processing Time**: 결제 10초 패턴 확인
3. **Queue Size**: 대기열 감소 추이
