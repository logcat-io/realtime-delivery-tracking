package com.logcat.tracking.external.persistence.jooq.delivery

import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.model.DeliveryStatusChange
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.delivery.port.DeliveryQueryPort
import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.jooq.generated.tables.references.DELIVERIES
import com.logcat.tracking.jooq.generated.tables.references.DELIVERY_STATUS_HISTORY
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

@Repository
class DeliveryJooqAdapter(
    private val dsl: DSLContext,
    private val idGenerator: IdGenerator,
) : DeliveryCommandPort, DeliveryQueryPort {

    private val deliveryColumns = listOf(
        DELIVERIES.ID,
        DELIVERIES.PRODUCT_ID,
        DELIVERIES.ORDER_ID,
        DELIVERIES.USER_ID,
        DELIVERIES.TRACKING_NUMBER,
        DELIVERIES.ADDRESS,
        DELIVERIES.STATUS,
        DELIVERIES.CREATED_AT,
        DELIVERIES.UPDATED_AT,
    )

    override fun save(delivery: Delivery): Delivery {
        dsl.insertInto(DELIVERIES)
            .set(DELIVERIES.ID, delivery.id)
            .set(DELIVERIES.PRODUCT_ID, delivery.productId)
            .set(DELIVERIES.ORDER_ID, delivery.orderId)
            .set(DELIVERIES.USER_ID, delivery.userId)
            .set(DELIVERIES.TRACKING_NUMBER, delivery.trackingNumber)
            .set(DELIVERIES.ADDRESS, delivery.address)
            .set(DELIVERIES.STATUS, delivery.status.name)
            .set(DELIVERIES.CREATED_AT, delivery.createdAt.atOffset(ZoneOffset.UTC))
            .set(DELIVERIES.UPDATED_AT, delivery.updatedAt.atOffset(ZoneOffset.UTC))
            .execute()

        return delivery
    }

    override fun updateStatus(
        deliveryId: UUID,
        status: DeliveryStatus,
        now: Instant
    ) {
        val updated = dsl.update(DELIVERIES)
            .set(DELIVERIES.STATUS, status.name)
            .set(DELIVERIES.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(DELIVERIES.ID.eq(deliveryId))
            .execute()

        check(updated == 1) { "Failed to update delivery status for ID: $deliveryId" }
    }

    override fun saveStatusHistory(
        deliveryId: UUID,
        change: DeliveryStatusChange
    ) {
        dsl.insertInto(DELIVERY_STATUS_HISTORY)
            .set(DELIVERY_STATUS_HISTORY.ID, idGenerator.nextId())
            .set(DELIVERY_STATUS_HISTORY.DELIVERY_ID, deliveryId)
            .set(DELIVERY_STATUS_HISTORY.FROM_STATUS, change.from?.name)
            .set(DELIVERY_STATUS_HISTORY.TO_STATUS, change.to.name)
            .set(DELIVERY_STATUS_HISTORY.REASON, change.reason)
            .set(DELIVERY_STATUS_HISTORY.CHANGED_AT, change.changedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    override fun findById(id: UUID): Delivery? =
        dsl.select(deliveryColumns)
            .from(DELIVERIES)
            .where(DELIVERIES.ID.eq(id))
            .fetchOne { it.toDelivery() }


    override fun findByUserId(
        userId: UUID,
        cursor: Instant?,
        size: Int
    ): List<Delivery> {
        val base = DELIVERIES.USER_ID.eq(userId)
        val condition = if (cursor != null) {
            base.and(DELIVERIES.CREATED_AT.lt(cursor.atOffset(ZoneOffset.UTC)))
        } else base

        return dsl.select(deliveryColumns)
            .from(DELIVERIES)
            .where(condition)
            .orderBy(DELIVERIES.CREATED_AT.desc())
            .limit(size)
            .fetch { it.toDelivery() }
    }

    private fun Record.toDelivery(): Delivery = Delivery(
        id = get(DELIVERIES.ID)!!,
        productId = get(DELIVERIES.PRODUCT_ID)!!,
        orderId = get(DELIVERIES.ORDER_ID)!!,
        userId = get(DELIVERIES.USER_ID)!!,
        trackingNumber = get(DELIVERIES.TRACKING_NUMBER)!!,
        address = get(DELIVERIES.ADDRESS)!!,
        status = DeliveryStatus.valueOf(get(DELIVERIES.STATUS)!!),
        createdAt = get(DELIVERIES.CREATED_AT)!!.toInstant(),
        updatedAt = get(DELIVERIES.UPDATED_AT)!!.toInstant(),
    )
}
