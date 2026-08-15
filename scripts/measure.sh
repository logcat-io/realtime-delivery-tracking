#!/usr/bin/env bash
# 정합 측정 — DB 에 커밋된 상태 변경 수(A)와 토픽 메시지 수(B)를 비교한다.
#
# 두 숫자가 어긋나면 커밋과 발행이 갈라진 것이다. 어긋난 방향이 원인을 가리킨다.
#
#   ./scripts/measure.sh count            지금 A·B 를 본다
#   ./scripts/measure.sh trial fresh 10   배송 10건 각 1회 전이   (기준선)
#   ./scripts/measure.sh trial same 10    배송 1건에 10회 전이 시도 (롤백 실험용)
#   ./scripts/measure.sh repeat 10 fresh 10   위를 10회 반복하고 요약
#
# 토픽 카운트는 컨슘이 아니라 end offset 합이다. 토픽이 커져도 O(1) 이고 1초면 끝난다.

set -uo pipefail

API=${API:-http://localhost:8080/api/v1/deliveries}
PG=${PG:-tracking-postgres}
BOOTSTRAP=${BOOTSTRAP:-localhost:19092}
TOPIC=${TOPIC:-delivery-status-events}
KAFKA_IMG=${KAFKA_IMG:-confluentinc/cp-kafka:7.6.1}

BODY='{"productId":"11111111-1111-1111-1111-111111111111",
       "orderId":"22222222-2222-2222-2222-222222222222",
       "userId":"33333333-3333-3333-3333-333333333333",
       "address":"측정용"}'

# ── 숫자 A: DB 에 커밋된 상태 변경 수 ────────────────────────────
count_db() {
  docker exec -i "$PG" psql -U tracking -d tracking -tAc \
    "SELECT count(*) FROM delivery_status_history;" 2>/dev/null | tr -d ' \n'
}

# ── 숫자 B: 토픽 메시지 수 = 파티션별 end offset 의 합 ───────────
count_topic() {
  docker run --rm --network host "$KAFKA_IMG" \
    kafka-run-class kafka.tools.GetOffsetShell \
    --bootstrap-server "$BOOTSTRAP" --topic "$TOPIC" 2>/dev/null \
  | awk -F: '{s+=$3} END {print s+0}'
}

# ── 프로듀서 버퍼가 비워질 때까지 대기 (sleep 추측 대신) ─────────
# 오프셋이 2회 연속 그대로면 정착으로 본다.
settle() {
  local prev cur stable=0 i=0
  prev=$(count_topic)
  while [ $stable -lt 2 ] && [ $i -lt 20 ]; do
    sleep 1; i=$((i+1))
    cur=$(count_topic)
    if [ "$cur" = "$prev" ]; then stable=$((stable+1)); else stable=0; fi
    prev=$cur
  done
}

new_delivery() {
  curl -s -X POST "$API" -H 'Content-Type: application/json' -d "$BODY" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["deliveryId"])' 2>/dev/null
}

change_status() {  # $1=id  $2=status
  curl -s -o /dev/null -w '%{http_code}' -X PUT "$API/$1/status" \
    -H 'Content-Type: application/json' -d "{\"status\":\"$2\"}"
}

# ── 판정 ────────────────────────────────────────────────────────
verdict() {  # $1=dA $2=dB $3=N
  if   [ "$2" -gt "$1" ]; then echo "유령  토픽에만 있다 (ΔB > ΔA)"
  elif [ "$1" -gt "$2" ]; then echo "유실  DB 에만 있다 (ΔA > ΔB)"
  elif [ "$1" -eq "$3" ]; then echo "정상  ΔA = ΔB = N"
  else                         echo "주의  일치하지만 N 과 다르다 (요청 자체가 실패했나?)"
  fi
}

# ── 한 번의 시행 ────────────────────────────────────────────────
# mode=fresh : 배송 N건, 각각 1회 전이       → 기준선용
# mode=same  : 배송 1건, N회 전이 시도       → 롤백되면 상태가 안 변해 반복 가능
trial() {
  local mode=$1 n=$2 quiet=${3:-}
  local ids=() id code ok=0

  if [ "$mode" = "fresh" ]; then
    for _ in $(seq 1 "$n"); do ids+=("$(new_delivery)"); done
  else
    id=$(new_delivery); for _ in $(seq 1 "$n"); do ids+=("$id"); done
  fi
  settle

  local a0 b0 a1 b1 dA dB
  a0=$(count_db); b0=$(count_topic)

  for id in "${ids[@]}"; do
    code=$(change_status "$id" PREPARING)
    [ "$code" = "200" ] && ok=$((ok+1))
  done
  settle

  a1=$(count_db); b1=$(count_topic)
  dA=$((a1-a0)); dB=$((b1-b0))

  if [ -z "$quiet" ]; then
    printf "  A %s → %s   ΔA=%s\n" "$a0" "$a1" "$dA"
    printf "  B %s → %s   ΔB=%s\n" "$b0" "$b1" "$dB"
    printf "  HTTP 200: %s/%s\n" "$ok" "$n"
    printf "  판정: %s\n" "$(verdict "$dA" "$dB" "$n")"
  fi
  echo "$dA $dB" > /tmp/.measure_last
}

case "${1:-}" in
  count)
    printf "  A (delivery_status_history)  %s\n" "$(count_db)"
    printf "  B (topic end offset 합)      %s\n" "$(count_topic)"
    ;;
  settle) settle; echo "  정착 완료 — B=$(count_topic)" ;;
  trial)
    echo "── 시행 (${2:-fresh} × ${3:-10}) ──"
    trial "${2:-fresh}" "${3:-10}"
    ;;
  repeat)
    R=${2:-10}; MODE=${3:-fresh}; N=${4:-10}
    echo "── ${R}회 반복 (${MODE} × ${N}) ──"
    printf "  %-4s %6s %6s  %s\n" "회차" "ΔA" "ΔB" "판정"
    mismatch=0
    for r in $(seq 1 "$R"); do
      trial "$MODE" "$N" quiet
      read -r dA dB < /tmp/.measure_last
      v=$(verdict "$dA" "$dB" "$N")
      [ "$dA" != "$dB" ] && mismatch=$((mismatch+1))
      printf "  %-4s %6s %6s  %s\n" "$r" "$dA" "$dB" "$v"
    done
    echo "  ─────────────────────────────"
    printf "  어긋난 시행: %s/%s\n" "$mismatch" "$R"
    ;;
  *)
    sed -n '2,10p' "$0" | sed 's/^# \?//'
    ;;
esac
