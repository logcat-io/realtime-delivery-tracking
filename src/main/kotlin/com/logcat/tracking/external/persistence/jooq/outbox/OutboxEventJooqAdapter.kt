package com.logcat.tracking.external.persistence.jooq.outbox

import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.external.persistence.jooq.outbox.projection.OutboxEvent
import com.logcat.tracking.jooq.generated.tables.references.OUTBOX_EVENTS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

@Repository
class OutboxEventJooqAdapter(
    private val dsl: DSLContext,
    private val idGenerator: IdGenerator,
) {

    fun saveEvent(
        aggregateType: String,
        aggregateId: UUID,
        eventType: String,
        payload: String,
    ) {
        dsl.insertInto(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.ID, idGenerator.nextId())
            .set(OUTBOX_EVENTS.AGGREGATE_TYPE, aggregateType)
            .set(OUTBOX_EVENTS.AGGREGATE_ID, aggregateId)
            .set(OUTBOX_EVENTS.EVENT_TYPE, eventType)
            .set(OUTBOX_EVENTS.STATUS, "PENDING")
            .set(OUTBOX_EVENTS.PAYLOAD, JSONB.jsonb(payload))
            .set(OUTBOX_EVENTS.CREATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
            .set(OUTBOX_EVENTS.RETRY_COUNT, 0)
            .execute()
    }

    fun findPendingEvents(limit: Int = 50): List<OutboxEvent> =
        dsl.select(
            OUTBOX_EVENTS.ID,
            OUTBOX_EVENTS.AGGREGATE_TYPE,
            OUTBOX_EVENTS.AGGREGATE_ID,
            OUTBOX_EVENTS.EVENT_TYPE,
            OUTBOX_EVENTS.PAYLOAD,
            OUTBOX_EVENTS.RETRY_COUNT,
        )
            .from(OUTBOX_EVENTS)
            .where(OUTBOX_EVENTS.STATUS.eq("PENDING"))
            .and(OUTBOX_EVENTS.RETRY_COUNT.le(5))
            .orderBy(OUTBOX_EVENTS.CREATED_AT.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { record ->
                OutboxEvent(
                    id = record[OUTBOX_EVENTS.ID]!!,
                    aggregateType = record[OUTBOX_EVENTS.AGGREGATE_TYPE]!!,
                    aggregateId = record[OUTBOX_EVENTS.AGGREGATE_ID]!!,
                    eventType = record[OUTBOX_EVENTS.EVENT_TYPE]!!,
                    payload = record[OUTBOX_EVENTS.PAYLOAD]!!.data(),
                    retryCount = record[OUTBOX_EVENTS.RETRY_COUNT]!!,
                )
            }

    fun markPublished(id: UUID) {
        dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.STATUS, "PUBLISHED")
            .set(OUTBOX_EVENTS.PUBLISHED_AT, Instant.now().atOffset(ZoneOffset.UTC))
            .where(OUTBOX_EVENTS.ID.eq(id))
            .execute()
    }

    fun markFailed(id: UUID) {
        dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.RETRY_COUNT, OUTBOX_EVENTS.RETRY_COUNT + 1)
            .where(OUTBOX_EVENTS.ID.eq(id))
            .execute()
    }
}
