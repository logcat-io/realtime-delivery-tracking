package com.logcat.tracking.application.delivery.usecase

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import com.logcat.tracking.core.delivery.exception.DeliveryNotFoundException
import com.logcat.tracking.core.delivery.port.DeliveryQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class TrackDeliveryUseCase(
    private val deliveryQueryPort: DeliveryQueryPort,
) {

    @Transactional(readOnly = true)
    fun execute(deliveryId: UUID): DeliveryStatusEvent {
        val delivery = deliveryQueryPort.findById(deliveryId)
            ?: throw DeliveryNotFoundException(deliveryId)

        return DeliveryStatusEvent(
            deliveryId = deliveryId,
            status = delivery.status.name,
            changedAt = delivery.updatedAt,
        )
    }
}
