#!/bin/bash

BASE_URL="http://localhost:8080"
TOTAL_USERS=1000
SEATS=100

echo "=============================================="
echo "🚀 티켓 예약 부하 테스트"
echo "=============================================="
echo "유저: ${TOTAL_USERS}명, 좌석: ${SEATS}개"
echo "목표: 100개 좌석 전부 SUCCESS"
echo "=============================================="

process_user() {
  local userId=$1
  
  # 대기열 진입
  curl -s -X POST "${BASE_URL}/api/v1/queue/enter?userId=${userId}" > /dev/null
  
  # Active 대기
  for i in $(seq 1 120); do
    status=$(curl -s "${BASE_URL}/api/v1/queue/status?userId=${userId}" 2>/dev/null | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    [ "$status" = "READY" ] && break
    sleep 1
  done
  
  # 1~100 좌석 순차 시도 (성공할 때까지)
  for seatId in $(seq 1 100 | sort -R 2>/dev/null || seq 1 100); do
    result=$(curl -s -X POST "${BASE_URL}/api/v1/reservations/reserve" \
      -H "Content-Type: application/json" \
      -d "{\"seatId\": ${seatId}, \"userId\": ${userId}}" 2>/dev/null)
    
    msg=$(echo "$result" | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
    
    if [[ "$msg" == *"SUCCESS"* ]]; then
      echo "✅ User ${userId} -> Seat ${seatId}"
      return 0
    fi
  done
  
  echo "❌ User ${userId}: 매진"
}

export -f process_user
export BASE_URL

echo ""
seq 1 $TOTAL_USERS | xargs -P 100 -I {} bash -c 'process_user {}'

echo ""
echo "=============================================="
echo "📊 최종 결과"
echo "=============================================="
curl -s "${BASE_URL}/actuator/prometheus" | grep -E "^(reservation_success|reservation_failed)_total"
echo ""
echo "예약 완료 좌석:"
docker exec ticket-mysql mysql -uroot -ppassword -se "SELECT COUNT(*) FROM ticket_db.seat WHERE is_reserved=1;" 2>/dev/null
