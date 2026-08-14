package com.logcat.tracking.core.delivery.port

import com.logcat.tracking.core.delivery.model.Delivery
import java.time.Instant
import java.util.*

interface DeliveryQueryPort {

    fun findById(id: UUID): Delivery?

    fun findByUserId(userId: UUID, cursor: Instant?, size: Int): List<Delivery>
}
