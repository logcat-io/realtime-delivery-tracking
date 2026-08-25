package com.logcat.tracking.external.scheduler

import com.logcat.tracking.external.persistence.jooq.outbox.OutboxEventJooqAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Component
class OutboxClaimReaper(
    private val outboxAdapter: OutboxEventJooqAdapter,
    private val transactionTemplate: TransactionTemplate,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OutboxClaimReaper::class.java)
    }

    @Scheduled(fixedDelay = 30000)
    fun reclaimStale() {
        val threshold = Instant.now().minus(Duration.ofHours(3))

        val reclaimed = transactionTemplate.execute {
            outboxAdapter.reclaimStaleClaims(threshold)
        } ?: 0

        if (reclaimed > 0) log.warn("reclaimed stale outbox claimed: count=$reclaimed")

    }

}
