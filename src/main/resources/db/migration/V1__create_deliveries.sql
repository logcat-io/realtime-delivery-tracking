-- =============================================================
-- deliveries: 배송 본체.
-- 커머스 주문 결제 완료 시 생성된다. 주문 서비스와는 order_id 로만 연결(논리 FK).
-- status 는 문자열로 저장 — Postgres enum 은 ALTER TYPE 비용이 크므로
-- 애플리케이션 레벨 DeliveryStatusManager 에서 전이 검증한다.
-- =============================================================

CREATE TABLE deliveries
(
    id              UUID PRIMARY KEY     DEFAULT uuid_generate_v7(),
    -- 어떤 상품 주문에서 생성된 배송인지.
    product_id      UUID        NOT NULL,
    order_id        UUID        NOT NULL, -- 논리 FK: 주문 서비스의 주문 ID (DB 를 공유하지 않는다)
    user_id         UUID        NOT NULL,
    -- 고객/상담사에게 노출하는 운송장 번호. DLV-20260810-A1B2C3 형식.
    tracking_number VARCHAR(30) NOT NULL UNIQUE,
    -- 배송 수령 주소 snapshot — 회원 주소가 변경되어도 이 배송의 주소는 불변.
    address         TEXT        NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'ORDER_RECEIVED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 사용자별 배송 목록 조회: WHERE user_id = ? ORDER BY created_at DESC
CREATE INDEX idx_deliveries_user_id ON deliveries (user_id);

-- 관리자/배송 기사 화면 상태별 필터링.
CREATE INDEX idx_deliveries_status ON deliveries (status);

-- 커서 기반 페이지네이션 정렬 키.
CREATE INDEX idx_deliveries_created ON deliveries (created_at DESC);

-- 주문 ID 로 배송 조회 (결제 완료 → 배송 생성 연동용).
CREATE INDEX idx_deliveries_order ON deliveries (order_id);


-- =============================================================
-- delivery_status_history: 상태 변경 이력.
-- 감사(audit) + 디버깅 + SSE 재연결 시 현재 상태 조회 용도.
-- =============================================================

CREATE TABLE delivery_status_history
(
    id          UUID PRIMARY KEY     DEFAULT uuid_generate_v7(),
    delivery_id UUID        NOT NULL, -- 논리 FK: deliveries.id
    from_status VARCHAR(30),          -- NULL = 최초 생성 시
    to_status   VARCHAR(30) NOT NULL,
    reason      TEXT,                 -- 배송 실패 사유 등
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_history ON delivery_status_history (delivery_id);
