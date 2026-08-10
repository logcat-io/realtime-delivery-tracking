# realtime-delivery-tracking

배송 상태 변경을 **DB가 커밋한 것만, 빠짐없이, 어느 인스턴스에 붙은 사용자에게든** 실시간으로 흘려보내는 파이프라인.

Kotlin / Spring Boot 4 / jOOQ / PostgreSQL 16 / Kafka / Redis.

---

## 왜 만드는가

이전 직장 결제 도메인에서 같은 종류의 사고를 두 번 겪었다.

**① "Kafka에 발행했는데 컨슈머가 못 받았다"**

```kotlin
@Transactional
fun processPayment(...) {
    paymentRepository.save(payment)              // 커밋 예정
    kafkaTemplate.send("payment-events", event)  // 비동기 발행
}
```

`send()`는 즉시 반환하고 실제 발행은 백그라운드에서 일어난다. 그 사이 트랜잭션이 롤백되면 **DB는 안 바뀌었는데 이벤트만 나간다.** 반대로 `.get()` 동기 대기 + 커밋 후 발행으로 바꿨더니, 이번엔 커밋 직후 발행 직전에 프로세스가 죽어서 **이벤트가 사라졌다.**

당시엔 이름을 몰랐다. 나중에 이게 **dual write** 라는 걸 알았다. 어느 순서로 놔도 두 저장소를 한 트랜잭션으로 묶을 수 없다.

**② "다중 인스턴스에서 일부 사용자만 알림을 못 받았다"**

인스턴스 3대, 사용자 X는 A에 연결. Kafka consumer group이 메시지를 B에 배정. B는 자기한테 X의 연결이 없으니 그냥 ack. **X는 영원히 못 받는다.**

Kafka consumer group은 부하를 나누는 도구지 모두에게 뿌리는 도구가 아니다. 그런데 "Kafka 쓰면 알아서 흘러간다"고 생각하고 있었다.

매번 임기응변으로 막고 "이번엔 안 터졌다"로 끝냈다. **안 터진 게 아니라 터졌는지 몰랐던 것이다.**

이 두 개를 구조로 막아본다.

---

## 진행 방식

도구를 먼저 깔지 않는다. **일부러 잘못 만든 상태에서 시작해 무엇이 먼저 아픈지 측정하고, 그 측정이 정당화하는 만큼만 도구를 들인다.**

각 단계는 세 가지를 남긴다 — ① 이 상태를 어떻게 만들었나 ② 무엇이 먼저 아팠나(측정치) ③ 그래서 무엇을 도입했나, **또는 도입하지 않기로 한 이유**.

| 단계 | 상태 |
|---|---|
| 0 | 상태 변경 트랜잭션 안에서 Kafka 직접 발행 (dual write — 의도된 결함) |
| 1 | Transactional Outbox |
| 2 | 다중 인스턴스 — consumer group fan-out 한계 재현 |
| 3 | Redis Pub/Sub 브로드캐스트 |
| 4 | SSE 재연결 공백 보완 |
| 5 | Outbox 트랜잭션 경계 분리 |
| 6 | DLQ · 재처리 |
| 7 | 기사 위치 좌표 — 전달 시맨틱스 이원화 |
| 8 | 부하 측정 |

---

## 실행

```bash
docker compose up -d

# jOOQ codegen 은 실제 스키마를 읽는다. 마이그레이션을 먼저 적용한다.
docker exec -i tracking-postgres psql -U tracking -d tracking \
  < src/main/resources/db/migration/V0__install_extensions.sql

./gradlew generateJooq
./gradlew bootRun
```

다중 인스턴스는 포트를 달리해 두 벌 띄운다.

```bash
./gradlew bootRun --args='--server.port=8080'
./gradlew bootRun --args='--server.port=8081'
```

포트: PostgreSQL `15433` · Redis `16379` · Kafka `19092` · 앱 `8080`

---

## 구조

```
core/          도메인 — 모델, 상태 전이 규칙, 포트. Spring·jOOQ·Kafka 를 모른다
application/   유스케이스 — 트랜잭션 경계
external/      어댑터 — 영속(jOOQ), 메시징(Kafka/Redis), 웹(SSE)
```

의존 방향은 `external → application → core` 단방향이다.
