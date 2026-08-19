package com.logcat.tracking.external.messaging.config

import com.logcat.tracking.external.messaging.redis.DeliveryStatusRedisPublisher
import com.logcat.tracking.external.messaging.redis.DeliveryStatusRedisSubscriber
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

@Configuration
class RedisMessageListenerConfig(
    private val redisConnectionFactory: RedisConnectionFactory,
    private val deliveryStatusRedisSubscriber: DeliveryStatusRedisSubscriber,
) {

    @Bean
    fun redisMessageListenerContainer(): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(redisConnectionFactory)

        container.addMessageListener(
            deliveryStatusRedisSubscriber,
            ChannelTopic(DeliveryStatusRedisPublisher.CHANNEL),
        )

        return container
    }
}
