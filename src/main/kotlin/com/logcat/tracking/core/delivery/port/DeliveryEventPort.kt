package com.logcat.tracking.core.delivery.port

import com.logcat.tracking.core.delivery.model.DeliveryStatus
import java.time.Instant
import java.util.*

interface DeliveryEventPort {

    fun publishStatusChanged(
        deliveryId: UUID,
        status: DeliveryStatus,
        changedAt: Instant,
    )
}
