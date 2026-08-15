# realtime-delivery-tracking

배송 상태 변경을 **DB가 커밋한 것만, 빠짐없이, 어느 인스턴스에 붙은 사용자에게든** 실시간으로 흘려보내는 파이프라인.

Kotlin / Spring Boot 4 / jOOQ / PostgreSQL 16 / Kafka / Redis

---

## 왜 시작했나

### 겪은 것 — 발행했는데 컨슈머가 못 받았다

이전 직장 결제 도메인에서 겪은 일이다.

```kotlin
@Transactional
fun processPayment(...) {
    paymentRepository.save(payment)              // 커밋 예정
    kafkaTemplate.send("payment-events", event)  // 비동기 발행
}
```

`send()`는 즉시 반환하고 실제 발행은 백그라운드에서 일어난다. 그 사이 트랜잭션이 롤백되면 **DB는 안 바뀌었는데 이벤트만 나간다.** 반대로 동기 대기 + 커밋 후 발행으로 바꿨더니, 이번엔 커밋 직후 발행 직전에 죽어서 **이벤트가 사라졌다.**

당시엔 이름을 몰랐다. 나중에 이게 **dual write** 라는 걸 알았다. 어느 순서로 놔도 두 저장소를 한 트랜잭션으로 묶을 수 없다. 그때는 임기응변으로 막고 "이번엔 안 터졌다"로 끝냈다. **안 터진 게 아니라 터졌는지 몰랐던 것이다.**

### 안 겪어 본 것 — 상태를 가진 프로세스를 늘리면?

다중 인스턴스 자체는 겪었다. 오토스케일링으로 늘었다 줄었다 하는 API 서버였고, 그걸로 문제가 난 적은 없다.

**문제가 없었던 건 그게 stateless 였기 때문이다.** 어느 인스턴스가 요청을 받든 결과가 같으니, 인스턴스가 몇 대인지는 신경 쓸 일이 아니었다.

SSE 는 거기서 갈린다. **연결이 프로세스 메모리에 산다.** 발행하는 프로세스와 연결을 가진 프로세스가 같아야만 동작한다는 뜻이고, 그 순간 "어느 인스턴스냐"가 갑자기 중요해진다. 인스턴스가 셋이고 사용자 X 가 A 에 붙어 있는데 이벤트가 B 로 가면 X 는 못 받는다 — Kafka consumer group 은 **부하를 나누는** 도구지 모두에게 뿌리는 도구가 아니니까.

여기까지는 문서로 읽어서 안다. **상태를 가진 프로세스를 늘려 놓고 직접 확인해 본 적은 없다.** Kafka 도 같다. 돌아가는 걸 봤지 내가 토픽을 설계하고 파티션 키를 정해 본 적은 없다.

### 그래서 순서를 뒤집었다

Kafka를 쓰고 싶었다. 그런데 **쓰고 싶다는 이유로 먼저 깔면, 이 프로젝트가 경계하려는 바로 그 짓**이 된다. 도구가 문제를 정의하게 두는 것.

그래서 SSE 하나로 시작한다. 단일 인스턴스에서는 그걸로 충분하고, 실제로 충분하다는 걸 ch1에서 확인했다. **인스턴스를 둘로 늘리는 순간 무엇이 먼저 깨지는지 보고, 그때 Kafka를 들인다.** Redis Pub/Sub도 같은 순서다 — Kafka만으로 안 되는 지점을 만난 다음에 판단한다.

기술을 배우는 게 목적이 아니라는 말이 아니다. **배우고 싶어서 시작했고, 그렇기 때문에 더더욱 "왜 필요한지"를 먼저 만들어 놓고 들이려는 것이다.** 필요를 겪지 않고 도입하면 다음에 같은 상황에서 그게 필요했는지 아닌지를 판단할 근거가 안 남는다.

---

## 지금까지 내린 결정

기술을 먼저 고르지 않았다. **각 결정마다 무엇을 얻고 무엇을 포기했는지**를 남긴다.

### 상태 전이 규칙을 한 곳만 소유하게 했다

`DeliveryStatusManager` 하나가 "어느 상태에서 어디로 갈 수 있나"를 전부 안다. 컨트롤러나 어댑터에서 `when(status)`로 **한 번만 더** 분기해도 진실이 둘이 되고, 둘은 반드시 어긋난다.

`CANCELLED`가 `PICKED_UP` 이후 불가한 것도 여기 있다 — 기사가 물건을 든 뒤엔 취소가 아니라 `FAILED`다. 이건 DB 제약으로 표현할 수 없는 도메인 규칙이라 코드가 져야 한다.

**대가:** 상태 하나 추가하면 전이표와 테스트를 같이 고쳐야 한다. 그래서 터미널 상태 테스트는 개별 조합 대신 `enum` 전체를 순회하게 짰다 — 값이 늘면 검증 범위가 따라서 는다.

### `status`를 DB enum 이 아니라 `VARCHAR` 로 뒀다

PostgreSQL `ENUM` 은 타입 안전하지만 **`ALTER TYPE ... ADD VALUE` 비용**이 붙는다. 값 추가가 트랜잭션 안에서 제한되고, 순서를 바꾸거나 값을 지우려면 타입을 새로 만들어 컬럼을 옮겨야 한다. 배송 상태는 도메인이 자라면 늘어나는 종류의 값이다.

그래서 저장은 `VARCHAR(30)`, **검증 책임은 애플리케이션이 진다.** DB는 문자열을 그대로 받고, 유효한 전이인지는 `DeliveryStatusManager` 가 판단한다.

**대가:** DB만 보면 아무 문자열이나 들어갈 수 있다. 애플리케이션을 우회한 직접 UPDATE 를 막지 못한다. 운영에서 손으로 상태를 고치는 일이 잦아지면 이 선택은 다시 봐야 한다.

### `core` 가 프레임워크를 모르게 했다

`core` 에는 Spring · jOOQ · Kafka · Redis import 가 **0건**이다. 순수 클래스라 `@Component` 를 못 붙이고, `application` 계층에서 손으로 빈 등록을 한다.

**대가:** 순수 클래스마다 등록 코드 한 줄이 는다.
**얻는 것:** 전이 규칙 테스트 16건이 **Spring Context 없이 0.03초**에 끝난다.

같은 이유로 `object` 와 빈을 갈랐다. 기준은 "상태가 있나"가 아니라 **"바꿔 끼울 일이 있나"** 다 — 규칙(`DeliveryStatusManager`)은 바뀌므로 빈, 포맷 유틸(`DeliveryTrackingNumberGenerator`)은 안 바뀌므로 `object`.

### SSE 레지스트리의 값을 `Set` 으로 뒀다

`Map<UUID, SseEmitter>` 1:1 이면 같은 배송을 보는 두 번째 화면이 첫 번째를 덮어쓴다. **덮어쓰인 쪽은 소켓이 살아 있어서 에러도 로그도 안 남는다.** 구매자·상담사·관리자가 같은 배송을 동시에 보는 건 예외가 아니라 정상 케이스다.

등록·제거는 `compute` / `computeIfPresent` 로 묶었다. `ConcurrentHashMap` 은 **개별 연산만** 원자적이라, "읽고 → 없으면 만들고 → 추가"를 나눠 쓰면 동시 등록 시 한쪽이 통째로 사라진다.

---

## 만들면서 알게 된 것

문서로 읽은 게 아니라 **직접 돌려서 관측한 것들.**

**Flyway 가 마이그레이션을 조용히 건너뛴다**

앱은 정상 기동하고 `/actuator/health` 도 `UP` 인데 테이블이 하나도 없었다. `public` 스키마에 함수 하나(`uuid_generate_v7()`)가 남아 있어서 Flyway 가 "비어 있지 않다"고 판정 → `baseline-on-migrate` 가 버전 1에 baseline 을 잡음 → **V0·V1 이 `≤ 1` 이라 통째로 스킵.** 로그에 ERROR 는 0건이고, 첫 쿼리를 날려야 터진다.

**SSE 는 첫 이벤트 전까지 응답 헤더도 안 보낸다**

연결 직후 클라이언트가 받는 건 **0바이트**다. 상태 변경이 한 번 일어나야 헤더(`Content-Type: text/event-stream`)와 프레임이 같이 나간다. 서버는 등록을 마쳤는데 클라이언트는 스트림이 열렸는지 알 수 없다.

**끊긴 연결은 다음 이벤트가 아니라 그다음 이벤트에서 걷힌다**

강제 종료 직후엔 콜백이 하나도 안 불린다. 그리고 닫힌 소켓에 대한 **첫 write 는 성공**한다 — 버퍼에 들어가기 때문이다. `Broken pipe` 는 두 번째 write 에서 나온다. 그래서 `onCompletion`·`onTimeout`·`onError` 세 콜백에 더해 **전송 실패 시 즉시 제거**하는 경로가 필요하다.

**`remove` 는 서로 다른 스레드에서 동시에 불린다**

죽은 구독자 하나에 `remove` 가 세 번, **두 스레드에서 1ms 안에** 들어왔다. 전송 실패 경로와 컨테이너 콜백이 같이 발화한 것이다. 멱등성만으로 부족하고 **동시 호출 안전성**이 필요하다는 뜻이라, 빈 집합 정리를 `computeIfPresent` 안에서 끝냈다.

---

## 진행 상황

| | 단계 | 상태 |
|---|---|---|
| ch0 | 도메인 · 상태 머신 · 생성/상태변경 API | ✅ |
| ch1 | SSE 실시간 전송 (단일 인스턴스) | ✅ |
| ch2 | 트랜잭션 안에서 Kafka 직접 발행 — **의도된 dual write** | 진행 중 |
| ch3~ | Outbox · 다중 인스턴스 fan-out · Redis Pub/Sub · DLQ · 좌표 · 부하 측정 | 예정 |

ch2 는 일부러 잘못 만드는 단계다. **dual write 를 먼저 재현하고, 무엇이 아픈지 측정한 다음, 그 측정이 정당화하는 만큼만 Outbox 를 들인다.**

### 지금 시점의 한계

- 전역 예외 핸들러가 없다. 전이 규칙 위반과 조회 실패가 모두 `500` 으로 나간다
- 단일 인스턴스 전용. 두 벌 띄우면 이벤트를 받은 쪽에만 프레임이 간다
- 이벤트가 더 안 나가는 배송의 죽은 emitter 는 타임아웃(30분)까지 남는다

---

## 실행

```bash
docker compose up -d postgres

# ⚠ 최초 1회만: V0·V1 을 psql 로 직접 넣는다.
#   generateJooq 는 실제 스키마를 읽어야 하는데, 생성 코드가 없으면 앱이 컴파일되지 않는다.
#   반드시 둘 다 넣을 것 — V0 만 넣으면 함수 때문에 Flyway 가 baseline 을 잡고
#   V0·V1 을 스킵해서, 테이블 없이 기동하는 상태가 된다.
for f in V0__install_extensions V1__create_deliveries; do
  docker exec -i tracking-postgres psql -U tracking -d tracking \
    < src/main/resources/db/migration/$f.sql
done

./gradlew generateJooq
./gradlew bootRun
```

스키마가 꼬였을 때는 통째로 지우고 앱을 띄우면 Flyway 가 전부 적용한다.

```bash
docker exec tracking-postgres psql -U tracking -d tracking \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO tracking;"
```

포트: PostgreSQL `15433` · Redis `16379` · Kafka `19092` · 앱 `8080`

```bash
# 실시간 스트림 (상태를 바꿔야 첫 프레임이 온다)
curl -N http://localhost:8080/api/v1/deliveries/sse/{deliveryId}/track
```

---

## 구조

```
core/          도메인 — 모델, 상태 전이 규칙, 포트. Spring·jOOQ·Kafka 를 모른다
application/   유스케이스 — 트랜잭션 경계는 여기서만 연다
external/      어댑터 — 영속(jOOQ), 웹(SSE), 메시징(Kafka/Redis)
```

의존 방향은 `external → application → core` 단방향이다. ch2 에서 SSE 어댑터가 Kafka 어댑터로 통째로 교체되는데, **`core` 와 `application` 은 한 글자도 안 바뀐다** — 그게 이 구조를 택한 값이다.
