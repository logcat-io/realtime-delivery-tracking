CREATE TABLE outbox_events
(
    id             UUID PRIMARY KEY      DEFAULT uuid_generate_v7(),
    -- 어떤 애그리거트에서 발생한 이벤트인지 식별.
    -- Delivery 외 다른 도메인도 같은 테이블을 공유할 수 있게 문자열로 둔다.
    aggregate_type VARCHAR(50)  NOT NULL,
    -- 애그리거트 식별자. Kafka 메시지 키로 그대로 쓰인다 → 파티션 순서 보장의 근거.
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    -- 이벤트 페이로드. JSONB 로 저장하여 인덱싱/부분 쿼리 가능.
    payload        JSONB        NOT NULL,
    -- PENDING → PUBLISHED | FAILED
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 발행 완료 시각. NULL 이면 아직 안 나갔다는 뜻 — 적체 관측의 기준점.
    published_at   TIMESTAMPTZ,
    -- 발행 실패 횟수. 임계 초과 처리는 ch7(DLQ/재시도)에서 다룬다.
    retry_count    INT          NOT NULL DEFAULT 0
);

-- OutboxPublisher 가 PENDING 이벤트를 created_at 순서로 폴링한다.
-- status + created_at 복합 인덱스로 효율적 스캔 보장.
CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);
