package com.logcat.tracking.external.messaging.kafka.delivery.dto

import java.time.Instant
import java.util.*

data class DeliveryStatusChangedEvent(
    val deliveryId: UUID,
    val status: String,
    val changedAt: Instant,
)
