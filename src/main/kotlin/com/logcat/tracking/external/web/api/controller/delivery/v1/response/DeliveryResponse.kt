package com.logcat.tracking.external.web.api.controller.delivery.v1.response

import com.logcat.tracking.application.delivery.dto.CreateDeliveryResult
import java.time.Instant
import java.util.*

data class CreateDeliveryResponse(
    val deliveryId: UUID,
    val trackingNumber: String,
    val status: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(r: CreateDeliveryResult) = CreateDeliveryResponse(
            deliveryId = r.deliveryId,
            trackingNumber = r.trackingNumber,
            status = r.status,
            createdAt = r.createdAt,
        )
    }
}
