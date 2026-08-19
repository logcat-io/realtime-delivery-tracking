package com.logcat.tracking.external.messaging.redis

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import com.logcat.tracking.external.web.api.sse.SseEmitterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DeliveryStatusRedisSubscriber(
    private val sseEmitterRegistry: SseEmitterRegistry,
    private val objectMapper: ObjectMapper,
) : MessageListener {

    companion object {
        private val log = LoggerFactory.getLogger(DeliveryStatusRedisSubscriber::class.java)
    }

    override fun onMessage(
        message: Message,
        pattern: ByteArray?
    ) {
        try {
            val payload = String(message.body, Charsets.UTF_8)
            val event = objectMapper.readValue(payload, DeliveryStatusChangedEvent::class.java)

            log.debug("Received delivery status changed event: {}", event)

            sseEmitterRegistry.send(
                deliveryId = event.deliveryId,
                DeliveryStatusEvent(
                    deliveryId = event.deliveryId,
                    status = event.status,
                    changedAt = event.changedAt,
                ),
            )
        } catch (e: Exception) {
            log.error(
                "Failed to handle message on channel ${DeliveryStatusRedisPublisher.CHANNEL}",
                e
            )
        }
    }
}
