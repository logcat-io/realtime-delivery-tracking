package com.logcat.tracking.application.delivery.usecase

import com.logcat.tracking.application.delivery.dto.CreateDeliveryCommand
import com.logcat.tracking.application.delivery.dto.CreateDeliveryResult
import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.core.delivery.service.DeliveryTrackingNumberGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class CreateDeliveryUseCase(
    private val deliveryCommandPort: DeliveryCommandPort,
    private val idGenerator: IdGenerator,
) {

    @Transactional
    fun execute(command: CreateDeliveryCommand): CreateDeliveryResult {
        val now = Instant.now()

        val delivery = Delivery.create(
            id = idGenerator.nextId(),
            productId = command.productId,
            orderId = command.orderId,
            userId = command.userId,
            trackingNumber = DeliveryTrackingNumberGenerator.generate(),
            address = command.address,
            now = now,
        )

        val saved = deliveryCommandPort.save(delivery)

        return CreateDeliveryResult(
            deliveryId = saved.id,
            trackingNumber = saved.trackingNumber,
            status = saved.status.name,
            createdAt = saved.createdAt,
        )
    }
}
