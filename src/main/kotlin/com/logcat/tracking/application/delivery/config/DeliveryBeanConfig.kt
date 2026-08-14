package com.logcat.tracking.application.delivery.config

import com.logcat.tracking.core.delivery.service.DeliveryStatusManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DeliveryBeanConfig {

    @Bean
    fun deliveryStatusManager(): DeliveryStatusManager =
        DeliveryStatusManager()
}
