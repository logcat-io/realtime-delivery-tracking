package com.logcat.tracking.external.messaging.kafka.delivery

import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DeliveryEventConsumer(
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(DeliveryEventConsumer::class.java)
    }

    @KafkaListener(
        topics = ["delivery-status-events"],
        groupId = "delivery-sse-group",
    )
    fun onStatusChanged(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            val event = objectMapper.readValue(
                record.value(),
                DeliveryStatusChangedEvent::class.java
            )

            log.info(
                "Consumed delivery status event: deliveryId={} status={} partition={} offset={}",
                event.deliveryId, event.status, record.partition(), record.offset(),
            )

            ack.acknowledge()
        } catch (e: Exception) {
            log.error("Failed to consume ${record.value()}", e)
            throw e
        }

    }
}
