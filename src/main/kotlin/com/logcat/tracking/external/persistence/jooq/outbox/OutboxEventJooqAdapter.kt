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

    fun markPublished(id: List<UUID>, claimedAt: Instant, now: Instant): Int =
        dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.STATUS, "PUBLISHED")
            .set(OUTBOX_EVENTS.PUBLISHED_AT, now.atOffset(ZoneOffset.UTC))
            .where(
                OUTBOX_EVENTS.ID.`in`(id)
                    .and(OUTBOX_EVENTS.CLAIMED_AT.eq(claimedAt.atOffset(ZoneOffset.UTC)))
            )
            .execute()

    fun markFailed(id: List<UUID>, claimedAt: Instant, now: Instant): Int =
        dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.RETRY_COUNT, OUTBOX_EVENTS.RETRY_COUNT + 1)
            .set(OUTBOX_EVENTS.STATUS, "PENDING")
            .setNull(OUTBOX_EVENTS.CLAIMED_AT)
            .where(
                OUTBOX_EVENTS.ID.`in`(id)
                    .and(
                        OUTBOX_EVENTS.CLAIMED_AT.eq(
                            claimedAt.atOffset(
                                ZoneOffset.UTC
                            )
                        )
                    )
            )
            .execute()

    fun reclaimStaleClaims(claimedBefore: Instant): Int =
        dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.RECLAIM_COUNT, OUTBOX_EVENTS.RECLAIM_COUNT + 1)
            .set(OUTBOX_EVENTS.STATUS, "PENDING")
            .setNull(OUTBOX_EVENTS.CLAIMED_AT)
            .where(OUTBOX_EVENTS.CLAIMED_AT.le(claimedBefore.atOffset(ZoneOffset.UTC))
                .and(OUTBOX_EVENTS.STATUS.eq("CLAIMED")))
            .execute()

    fun claimPendingEvents(limit: Int, now: Instant): List<OutboxEvent> {
        val ids = dsl.select(OUTBOX_EVENTS.ID)
            .from(OUTBOX_EVENTS)
            .where(OUTBOX_EVENTS.STATUS.eq("PENDING"))
            .orderBy(OUTBOX_EVENTS.CREATED_AT.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch(OUTBOX_EVENTS.ID)

        if (ids.isEmpty()) return emptyList()

        return dsl.update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.STATUS, "CLAIMED")
            .set(OUTBOX_EVENTS.CLAIMED_AT, now.atOffset(ZoneOffset.UTC))
            .where(OUTBOX_EVENTS.ID.`in`(ids))
            .returning(
                OUTBOX_EVENTS.ID,
                OUTBOX_EVENTS.AGGREGATE_TYPE,
                OUTBOX_EVENTS.AGGREGATE_ID,
                OUTBOX_EVENTS.EVENT_TYPE,
                OUTBOX_EVENTS.PAYLOAD,
                OUTBOX_EVENTS.RETRY_COUNT,
            )
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
    }
}
