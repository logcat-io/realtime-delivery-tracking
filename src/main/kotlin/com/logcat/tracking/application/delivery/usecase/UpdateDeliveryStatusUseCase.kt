package com.logcat.tracking.application.delivery.usecase

import com.logcat.tracking.core.delivery.exception.DeliveryNotFoundException
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.model.DeliveryStatusChange
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.delivery.port.DeliveryEventPort
import com.logcat.tracking.core.delivery.port.DeliveryQueryPort
import com.logcat.tracking.core.delivery.service.DeliveryStatusManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class UpdateDeliveryStatusUseCase(
    private val deliveryQueryPort: DeliveryQueryPort,
    private val deliveryCommandPort: DeliveryCommandPort,
    private val deliveryEventPort: DeliveryEventPort,
    private val statusManager: DeliveryStatusManager,
) {

    @Transactional
    fun execute(deliveryId: UUID, target: DeliveryStatus, reason: String? = null) {
        val delivery = deliveryQueryPort.findById(deliveryId)
            ?: throw DeliveryNotFoundException(deliveryId)

        val validated = statusManager.transition(delivery.status, target)
        val now = Instant.now()

        deliveryCommandPort.updateStatus(deliveryId, validated, now)
        deliveryCommandPort.saveStatusHistory(
            deliveryId = deliveryId,
            DeliveryStatusChange(
                from  = delivery.status,
                to = validated,
                reason = reason,
                changedAt = now,
            )
        )

        // 외부로 나간다. <- 정합성에 문제가 될 수 있는 부분
        deliveryEventPort.publishStatusChanged(delivery.id, validated, now)
    }
}
