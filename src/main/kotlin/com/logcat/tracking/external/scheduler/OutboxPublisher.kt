package com.logcat.tracking.external.scheduler

import com.logcat.tracking.external.persistence.jooq.outbox.OutboxEventJooqAdapter
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

@Component
class OutboxPublisher(
    private val outboxAdapter: OutboxEventJooqAdapter,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val transactionTemplate: TransactionTemplate,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OutboxPublisher::class.java)

        const val TOPIC = "delivery-status-events"
        const val BATCH_SIZE = 50
    }

    @Scheduled(fixedDelay = 1000)
    fun publishPendingEvents() {
        val now = Instant.now()

        val claims = transactionTemplate.execute {
            outboxAdapter.claimPendingEvents(BATCH_SIZE, now)
        } ?: emptyList()

        if (claims.isEmpty()) return

        val published = mutableListOf<UUID>()
        val failed = mutableListOf<UUID>()


        for ((id, _, aggregateId, _, payload, retryCount) in claims) {
            try {
                kafkaTemplate.send(
                    TOPIC,
                    aggregateId.toString(),
                    payload,
                ).get()

                published += id
            } catch (e: Exception) {
                log.error("Outbox publish failed: id={}, retry={}", id, retryCount, e)
                failed += id
            }
        }

        transactionTemplate.execute {
            val p = if (published.isEmpty()) 0 else outboxAdapter.markPublished(
                published,
                now,
                Instant.now()
            )

            val f =
                if (failed.isEmpty()) 0 else outboxAdapter.markFailed(failed, now, Instant.now())

            val lost = (published.size - p) + (failed.size - f)
            if (lost > 0) log.warn("outbox claim lost during publish: count=$lost")
        }
    }
}
