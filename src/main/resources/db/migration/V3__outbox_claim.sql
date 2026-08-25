ALTER TABLE outbox_events
    ADD COLUMN claimed_at    TIMESTAMPTZ,
    ADD COLUMN reclaim_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN outbox_events.claimed_at IS
    '발행을 위해 선점된 시각. CLAIMED 상태에서만 의미가 있다.
     두 가지로 쓴다 — (1) 회수(reaper)의 기준값, (2) 결과 반영 시 fencing 토큰.';
COMMENT ON COLUMN outbox_events.reclaim_count IS
    '선점한 워커가 결과를 남기지 못하고 사라져 회수된 횟수.
     retry_count(시도했고 실패)와 원인이 다르므로 카운터를 분리한다.';

CREATE INDEX idx_outbox_claimed_at
    ON outbox_events (claimed_at)
    WHERE status = 'CLAIMED';
