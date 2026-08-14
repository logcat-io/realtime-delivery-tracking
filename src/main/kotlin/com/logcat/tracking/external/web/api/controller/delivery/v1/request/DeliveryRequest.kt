package com.logcat.tracking.external.web.api.controller.delivery.v1.request

import com.logcat.tracking.application.delivery.dto.CreateDeliveryCommand
import java.util.*

data class CreateDeliveryRequest(
    val productId: UUID,
    val orderId: UUID,
    val userId: UUID,
    val address: String,
) {

    fun toCommand() = CreateDeliveryCommand(
        productId = productId,
        orderId = orderId,
        userId = userId,
        address = address,
    )
}

data class ChangeDeliveryStatusRequest(
    val status: String,
    val reason: String? = null,
)
