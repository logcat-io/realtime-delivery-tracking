package com.logcat.tracking.external.messaging.redis

import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DeliveryStatusRedisPublisher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val CHANNEL = "delivery-status"
    }

    fun publish(event: DeliveryStatusChangedEvent) {
        val json = objectMapper.writeValueAsString(event)
        redisTemplate.convertAndSend(CHANNEL, json)
    }
}
