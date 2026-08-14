package com.logcat.tracking.core.delivery.model

enum class DeliveryStatus {
    ORDER_RECEIVED,     // 주문 접수 (구매 완료 → 배송 생성 시 초기 상태)
    PREPARING,          // 상품 준비 중
    READY_FOR_PICKUP,   // 픽업 대기 (배송 기사 배정 완료)
    PICKED_UP,          // 배송 기사 픽업 완료
    DELIVERING,         // 배송 중
    ARRIVED,            // 배송 완료 (터미널)
    CANCELLED,          // 취소 (터미널 — PICKED_UP 이후 불가)
    FAILED,             // 배송 실패 — 수취인 부재 등 (터미널)
}
