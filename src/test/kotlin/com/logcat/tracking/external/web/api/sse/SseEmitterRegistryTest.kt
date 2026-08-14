package com.logcat.tracking.external.web.api.sse

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Instant
import java.util.UUID

class SseEmitterRegistryTest {

    private val registry = SseEmitterRegistry()

    private fun event(deliveryId: UUID) = DeliveryStatusEvent(
        deliveryId = deliveryId,
        status = DeliveryStatus.PREPARING.name,
        changedAt = Instant.now(),
    )

    @Test
    fun `같은 deliveryId 에 emitter 2개를 등록하면 둘 다 프레임을 받는다`() {
        val deliveryId = UUID.randomUUID()
        val buyer = mock<SseEmitter>()
        val agent = mock<SseEmitter>()

        registry.register(deliveryId, buyer)
        registry.register(deliveryId, agent)

        registry.send(deliveryId, event(deliveryId))

        // 1:1 Map 이었다면 agent 가 buyer 를 덮어써서 buyer 는 0회가 된다
        verify(buyer, times(1)).send(any<SseEmitter.SseEventBuilder>())
        verify(agent, times(1)).send(any<SseEmitter.SseEventBuilder>())
    }

    @Test
    fun `remove 된 emitter 는 더 이상 프레임을 받지 않는다`() {
        val deliveryId = UUID.randomUUID()
        val emitter = mock<SseEmitter>()

        registry.register(deliveryId, emitter)
        registry.send(deliveryId, event(deliveryId))

        registry.remove(deliveryId, emitter)
        registry.send(deliveryId, event(deliveryId))

        // 두 번 보냈지만 remove 이후의 1회는 도달하지 않는다
        verify(emitter, times(1)).send(any<SseEmitter.SseEventBuilder>())
    }

    @Test
    fun `send 가 실패한 emitter 는 그 자리에서 걷힌다`() {
        val deliveryId = UUID.randomUUID()
        val dead = mock<SseEmitter> {
            on { send(any<SseEmitter.SseEventBuilder>()) } doThrow IOException("client gone")
        }
        val alive = mock<SseEmitter>()

        registry.register(deliveryId, dead)
        registry.register(deliveryId, alive)

        registry.send(deliveryId, event(deliveryId))   // dead 실패 → 자동 제거
        registry.send(deliveryId, event(deliveryId))

        // dead 는 첫 번째만, alive 는 두 번 다 받는다.
        // dead 의 예외가 forEach 밖으로 튀었다면 alive 는 1회에 그친다.
        verify(dead, times(1)).send(any<SseEmitter.SseEventBuilder>())
        verify(alive, times(2)).send(any<SseEmitter.SseEventBuilder>())
    }

    @Test
    fun `등록된 적 없는 deliveryId 로 send 해도 예외가 나지 않는다`() {
        registry.send(UUID.randomUUID(), event(UUID.randomUUID()))
    }
}
