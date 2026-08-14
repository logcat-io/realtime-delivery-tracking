package com.logcat.tracking.application.delivery.dto

import java.time.Instant
import java.util.*

data class CreateDeliveryCommand(
    val productId: UUID,
    val orderId: UUID,
    val userId: UUID,
    val address: String,
)

data class CreateDeliveryResult(
    val deliveryId: UUID,
    val trackingNumber: String,
    val status: String, // 도메인 리팩터링과 API 스킴을 분리하기 위해서 string
    val createdAt: Instant,
)

data class DeliveryStatusEvent(
    val deliveryId: UUID,
    val status: String,
    val changedAt: Instant,
)
