package com.logcat.tracking.core.delivery.model

import java.time.Instant
import java.util.*

data class Delivery(
    val id: UUID,
    val productId: UUID,
    val orderId: UUID,
    val userId: UUID,
    val trackingNumber: String,
    val address: String,
    val status: DeliveryStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        // kotlin 의 data class 는 copy() 를 자동 생성하는데, copy() 는 리플렉션이나 주 생성자(primary constructor)를 다시 호출한다.
        // 주 생성자가 호출되면 init 블록도 다시 실행된다.

        require(address.isNotBlank()) { "address must not be blank" }
        require(trackingNumber.isNotBlank()) { "trackingNumber must not be blank" }
        require(!createdAt.isAfter(updatedAt)) { "createdAt must be after updatedAt $createdAt" }
    }

    fun withStatus(next: DeliveryStatus, now: Instant): Delivery =
        copy(status = next, updatedAt = now)

    companion object {
        fun create(
            id: UUID,
            productId: UUID,
            orderId: UUID,
            userId: UUID,
            trackingNumber: String,
            address: String,
            now: Instant,
        ): Delivery = Delivery(
            id = id,
            productId = productId,
            orderId = orderId,
            userId = userId,
            trackingNumber = trackingNumber,
            address = address,
            status = DeliveryStatus.ORDER_RECEIVED,
            createdAt = now,
            updatedAt = now,
        )
    }
}
