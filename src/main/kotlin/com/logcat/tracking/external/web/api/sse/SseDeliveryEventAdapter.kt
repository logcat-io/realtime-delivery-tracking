package com.logcat.tracking.external.web.api.sse

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryEventPort
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.*

//@Component
class SseDeliveryEventAdapter(
    private val registry: SseEmitterRegistry,
) : DeliveryEventPort {

    override fun publishStatusChanged(
        deliveryId: UUID,
        status: DeliveryStatus,
        changedAt: Instant
    ) {
        registry.send(
            deliveryId,
            DeliveryStatusEvent(
                deliveryId = deliveryId,
                status = status.name,
                changedAt = changedAt,
            ),
        )
    }
}
