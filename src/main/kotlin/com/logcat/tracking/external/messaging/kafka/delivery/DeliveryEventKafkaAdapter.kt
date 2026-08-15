package com.logcat.tracking.external.messaging.kafka.delivery

import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryEventPort
import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.*

@Component
class DeliveryEventKafkaAdapter(
    private val kafkaTemplate: KafkaTemplate<String, String>,
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

        kafkaTemplate.send(
            "delivery-status-events",
            deliveryId.toString(),
            objectMapper.writeValueAsString(event),
        )
    }
}
