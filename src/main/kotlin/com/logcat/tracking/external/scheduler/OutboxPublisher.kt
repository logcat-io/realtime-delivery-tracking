package com.logcat.tracking.external.scheduler

import com.logcat.tracking.external.persistence.jooq.outbox.OutboxEventJooqAdapter
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPublisher(
    private val outboxAdapter: OutboxEventJooqAdapter,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OutboxPublisher::class.java)

        const val TOPIC = "delivery-status-events"
    }

    @Transactional
    @Scheduled(fixedDelay = 1000)
    fun publishPendingEvents() {
        val events = outboxAdapter.findPendingEvents(limit = 50)
        if (events.isEmpty()) return

        for ((id, _, aggregateId, _, payload, retryCount) in events) {
            try {
                kafkaTemplate.send(
                    TOPIC,
                    aggregateId.toString(),
                    payload,
                ).get()

                outboxAdapter.markPublished(id)
            } catch (e: Exception) {
                outboxAdapter.markFailed(id)
                log.error("Outbox publish failed: id={}, retry={}", id, retryCount, e)
            }
        }
    }
}
