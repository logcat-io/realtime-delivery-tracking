package com.logcat.tracking.core.delivery.service

import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.model.DeliveryStatus.*

class DeliveryStatusManager {

    private val transitions: Map<DeliveryStatus, Set<DeliveryStatus>> = mapOf(
        ORDER_RECEIVED to setOf(PREPARING, CANCELLED),
        PREPARING to setOf(READY_FOR_PICKUP, CANCELLED),
        READY_FOR_PICKUP to setOf(PICKED_UP, CANCELLED),
        PICKED_UP to setOf(DELIVERING),
        DELIVERING to setOf(ARRIVED, FAILED),
    )

    fun transition(current: DeliveryStatus, target: DeliveryStatus): DeliveryStatus {
        val allowed = transitions[current]
            ?: throw IllegalStateException("Terminal status: $current - no transition allowed")

        require(target in allowed) {
            "Invalid delivery status: $current -> $target"
        }

        return target
    }
}
