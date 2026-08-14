package com.logcat.tracking.external.web.api.controller.delivery.v1

import com.logcat.tracking.external.web.api.sse.SseEmitterRegistry
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*

@RestController
@RequestMapping("/api/v1/deliveries/sse")
class DeliverySseController(
    private val sseEmitterRegistry: SseEmitterRegistry,
) {

    @GetMapping("/{deliveryId}/track", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun track(@PathVariable deliveryId: UUID): SseEmitter {
        val emitter = SseEmitter(30 * 60 * 1000L)

        sseEmitterRegistry.register(deliveryId, emitter)

        emitter.onCompletion { sseEmitterRegistry.remove(deliveryId, emitter) }
        emitter.onTimeout { sseEmitterRegistry.remove(deliveryId, emitter) }
        emitter.onError { sseEmitterRegistry.remove(deliveryId, emitter) }

        return emitter
    }
}
