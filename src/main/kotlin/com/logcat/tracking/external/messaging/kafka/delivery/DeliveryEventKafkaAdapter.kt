package com.logcat.tracking.external.messaging.kafka.delivery

import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryEventPort
import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import com.logcat.tracking.external.persistence.jooq.outbox.OutboxEventJooqAdapter
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.*

@Component
class DeliveryEventKafkaAdapter(
    private val outboxAdapter: OutboxEventJooqAdapter,
    private val objectMapper: ObjectMapper,
) : DeliveryEventPort {

    override fun publishStatusChanged(
        deliveryId: UUID,
        status: DeliveryStatus,
        changedAt: Instant
    ) {
        val event = DeliveryStatusChangedEvent(
            deliveryId = deliveryId,
            status = status.name,
            changedAt = changedAt,
        )

        outboxAdapter.saveEvent(
            aggregateType = "DELIVERY",
            aggregateId = deliveryId,
            eventType = "DELIVERY_STATUS_CHANGED",
            payload = objectMapper.writeValueAsString(event),
        )
    }
}
