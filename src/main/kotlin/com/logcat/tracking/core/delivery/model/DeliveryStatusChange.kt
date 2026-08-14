package com.logcat.tracking.core.delivery.model

import java.time.Instant

data class DeliveryStatusChange(
    val from: DeliveryStatus?,
    val to: DeliveryStatus,
    val reason: String?,
    val changedAt: Instant,
)
