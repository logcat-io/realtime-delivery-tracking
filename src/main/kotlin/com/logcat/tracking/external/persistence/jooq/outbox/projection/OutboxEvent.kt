package com.logcat.tracking.external.persistence.jooq.outbox.projection

import java.util.UUID

data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val retryCount: Int,
)
