# API 사용 가이드

## 📌 API 플로우

```
1. 대기열 진입 → 2. 상태 확인 (폴링) → 3. 예약 진행
```

---

## 🔵 1. 대기열 진입

### Endpoint
```
POST /api/v1/queue/enter?userId={userId}
```

### Request
```bash
curl -X POST "http://localhost:8080/api/v1/queue/enter?userId=100"
```

### Response

**Case 1: 대기 중**
```json
{
  "userId": 100,
  "status": "WAITING",
  "rank": 5,
  "message": "현재 대기 중입니다. 순번: 5번"
}
```

**Case 2: 즉시 입장 (Active User 자리 있음)**
```json
{
  "userId": 100,
  "status": "READY",
  "rank": null,
  "message": "입장 완료! 예약을 진행하세요."
}
```

---

## 🟢 2. 대기열 상태 확인

### Endpoint
```
GET /api/v1/queue/status?userId={userId}
```

### Request
```bash
curl "http://localhost:8080/api/v1/queue/status?userId=100"
```

### Response

**Case 1: 여전히 대기 중**
```json
{
  "userId": 100,
  "status": "WAITING",
  "rank": 3,
  "message": "현재 대기 중입니다. 순번: 3번",
  "activeUserCount": 100
}
```

**Case 2: 입장 완료 (Scheduler가 Active User로 이동)**
```json
{
  "userId": 100,
  "status": "READY",
  "rank": null,
  "message": "입장 완료! 예약이 가능합니다.",
  "activeUserCount": 98
}
```

### 폴링 권장 간격
- **1~3초마다** 상태 확인
- `status: "READY"`가 되면 예약 API 호출

---

## 🟡 3. 예약 진행 (Active User만 가능)

### Endpoint
```
POST /api/v1/reservations/reserve
```

### Request
```bash
curl -X POST "http://localhost:8080/api/v1/reservations/reserve" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 100,
    "seatId": 1
  }'
```

### Response

**Case 1: 성공**
```json
{
  "status": "SUCCESS",
  "message": "SUCCESS: 예약이 확정되었습니다!"
}
```

**Case 2: 대기열 미진입 (403 Forbidden)**
```json
{
  "status": "FAIL",
  "message": "대기열 진입이 필요합니다. /api/v1/queue/enter를 먼저 호출하세요."
}
```

**Case 3: 좌석 선점 실패 (409 Conflict)**
```json
{
  "status": "FAIL",
  "message": "FAIL: 현재 다른 사용자가 결제 진행 중입니다."
}
```

**Case 4: 결제 실패**
```json
{
  "status": "FAIL",
  "message": "FAIL: 결제가 실패하여 좌석 선점이 취소되었습니다."
}
```

---

## 🔴 4. 예약 취소

### Endpoint
```
POST /api/v1/reservations/cancel
```

### Request
```bash
curl -X POST "http://localhost:8080/api/v1/reservations/cancel" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 100,
    "seatId": 1
  }'
```

---

## ⚫ 5. 대기열 이탈

### Endpoint
```
DELETE /api/v1/queue?userId={userId}
```

### Request
```bash
curl -X DELETE "http://localhost:8080/api/v1/queue?userId=100"
```

### Response
```
대기열에서 나갔습니다.
```

---

## 📊 Postman 테스트 시나리오

### 시나리오 1: 정상 플로우

```
1. 대기열 진입
   POST /api/v1/queue/enter?userId=100
   → status: "WAITING", rank: 1

2. 1초 대기 (Scheduler가 Active User로 이동)

3. 상태 확인
   GET /api/v1/queue/status?userId=100
   → status: "READY"

4. 예약 진행
   POST /api/v1/reservations/reserve
   Body: {"userId": 100, "seatId": 1}
   → "SUCCESS: 예약이 확정되었습니다!"
```

### 시나리오 2: 대기열 미진입 시도

```
1. 대기열 진입 없이 바로 예약 시도
   POST /api/v1/reservations/reserve
   Body: {"userId": 200, "seatId": 2}
   → 403 Forbidden
   → "대기열 진입이 필요합니다..."
```

### 시나리오 3: 동시 예약 충돌

```
1. 유저 A: 예약 진행
   POST /api/v1/reservations/reserve
   Body: {"userId": 100, "seatId": 1}
   → 결제 진행 중 (10초)

2. 유저 B: 같은 좌석 예약 시도
   POST /api/v1/reservations/reserve
   Body: {"userId": 101, "seatId": 1}
   → 409 Conflict
   → "FAIL: 현재 다른 사용자가 결제 진행 중입니다."
```

---

## 🎯 프론트엔드 구현 예시

### React + Polling

```javascript
// 1. 대기열 진입
const enterQueue = async (userId) => {
  const response = await fetch(`/api/v1/queue/enter?userId=${userId}`, {
    method: 'POST'
  });
  const data = await response.json();

  if (data.status === 'READY') {
    // 즉시 예약 가능
    return true;
  } else {
    // 폴링 시작
    startPolling(userId);
    return false;
  }
};

// 2. 폴링으로 상태 확인 (3초마다)
const startPolling = (userId) => {
  const interval = setInterval(async () => {
    const response = await fetch(`/api/v1/queue/status?userId=${userId}`);
    const data = await response.json();

    if (data.status === 'READY') {
      clearInterval(interval);
      alert('입장 완료! 예약이 가능합니다.');
      enableReservationButton(); // 예약 버튼 활성화
    } else {
      updateRankDisplay(data.rank); // UI에 순번 표시
    }
  }, 3000);
};

// 3. 예약 진행
const reserve = async (userId, seatId) => {
  const response = await fetch('/api/v1/reservations/reserve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, seatId })
  });

  const data = await response.json();

  if (response.status === 403) {
    alert('대기열 진입이 필요합니다.');
    return;
  }

  if (data.status === 'SUCCESS') {
    alert('예약 성공!');
  } else {
    alert(data.message);
  }
};
```

---

## 🔍 모니터링 (Redis CLI)

### Active User 확인
```bash
# 현재 활성 유저 목록
redis-cli KEYS "ticket:active:users:*"

# 특정 유저 TTL 확인
redis-cli TTL "ticket:active:users:100"

# 현재 활성 유저 수
redis-cli KEYS "ticket:active:users:*" | wc -l
```

### 대기열 확인
```bash
# 대기 중인 유저 수
redis-cli ZCARD "ticket:waiting:queue"

# 상위 10명 확인
redis-cli ZRANGE "ticket:waiting:queue" 0 9 WITHSCORES
```

---

## ⚠️ 주의사항

1. **대기열 진입은 필수**
   - `/api/v1/reservations/reserve`를 바로 호출하면 403 에러

2. **Active User TTL**
   - 5분 동안 활동 없으면 자동 제거
   - 다시 대기열 진입 필요

3. **폴링 간격**
   - 너무 짧으면 서버 부하 증가
   - 권장: 3초 간격

4. **Scheduler 주기**
   - 1초마다 10명씩 Active User로 이동
   - 100명 넘으면 추가 안 됨

---

## 📈 성능 지표

| 지표 | 값 |
|------|-----|
| **Active User 최대** | 100명 |
| **대기열 → Active 이동** | 1초마다 10명 |
| **Active User TTL** | 5분 |
| **결제 시뮬레이션** | 10초 |
| **결제 성공률** | 80% |

---

## 🎓 면접 대비

**Q: 왜 API를 분리했나요?**

> "기존에는 하나의 API로 대기열 진입과 예약을 모두 처리해서, 클라이언트가 같은 API를 2번 호출해야 했습니다. 이는 UX가 나쁘고, 폴링 구현도 복잡했습니다.
>
> 분리 후에는:
> 1. `/queue/enter` - 대기열 진입 (1회만)
> 2. `/queue/status` - 폴링으로 상태 확인 (여러 번)
> 3. `/reservations/reserve` - 예약 진행 (1회만)
>
> 이렇게 **역할을 명확히 분리**해서 RESTful하고, 테스트도 쉬워졌습니다."
