package com.logcat.tracking.external.web.api.sse

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class SseEmitterRegistry {

    companion object {
        private val log = LoggerFactory.getLogger(SseEmitterRegistry::class.java)
    }

    private val emitters = ConcurrentHashMap<UUID, MutableSet<SseEmitter>>()

    fun register(deliveryId: UUID, emitter: SseEmitter) {
        emitters.compute(deliveryId) { _, current ->
            (current ?: ConcurrentHashMap.newKeySet()).also { it.add(emitter) }
        }

        log.debug("SSE emitter registered: deliveryId = $deliveryId")
    }

    fun remove(deliveryId: UUID, emitter: SseEmitter) {
        emitters.computeIfPresent(deliveryId) { _, set ->
            set.remove(emitter)

            if (set.isEmpty()) null else set
        }

        log.debug("SSE emitter removed: deliveryId = $deliveryId")
    }

    fun send(deliveryId:UUID, event: DeliveryStatusEvent) {
        val targets = emitters[deliveryId] ?: return
        val failed = mutableListOf<SseEmitter>()

        targets.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("status")
                        .data(event),
                )
            } catch (e: Exception) {
                log.debug("SSE emitter sending error: ", e)
                failed.add(emitter)
            }
        }

        failed.forEach { remove(deliveryId, it) }
    }
}
