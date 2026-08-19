package com.logcat.tracking.external.messaging.kafka.delivery

import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import com.logcat.tracking.external.messaging.redis.DeliveryStatusRedisPublisher
import com.logcat.tracking.external.scheduler.OutboxPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DeliveryEventConsumer(
    private val redisPublisher: DeliveryStatusRedisPublisher,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(DeliveryEventConsumer::class.java)
    }

    @KafkaListener(
        topics = [OutboxPublisher.TOPIC],
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

            redisPublisher.publish(event)

            ack.acknowledge() // kafka 의 소비가 끝났다는 것이지. 사용자에게 도달했다는 것과는 다르다.

            log.info(
                "Consumed delivery status event: deliveryId={} status={} partition={} offset={}",
                event.deliveryId, event.status, record.partition(), record.offset(),
            )

        } catch (e: Exception) {
            log.error("Failed to consume ${record.value()}", e)
            throw e
        }

    }
}
