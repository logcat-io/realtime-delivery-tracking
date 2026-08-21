package com.logcat.tracking.core.delivery.port

import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.model.DeliveryStatusChange
import java.time.Instant
import java.util.UUID

interface DeliveryCommandPort {

    fun save(delivery: Delivery): Delivery

    fun updateStatus(deliveryId: UUID, status: DeliveryStatus, expected: DeliveryStatus, now: Instant)

    fun saveStatusHistory(deliveryId: UUID, change: DeliveryStatusChange)
}
