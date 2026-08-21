package com.logcat.tracking.external.web.api.controller.delivery.v1

import com.logcat.tracking.application.delivery.dto.DeliveryStatusEvent
import com.logcat.tracking.application.delivery.usecase.TrackDeliveryUseCase
import com.logcat.tracking.core.delivery.exception.DeliveryNotFoundException
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.external.web.api.sse.SseEmitterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

@DisplayName("DeliverySseController: 구독 시작과 스냅샷의 순서")
class DeliverySseControllerTest {

    private lateinit var sut: DeliverySseController
    private lateinit var registry: SseEmitterRegistry
    private lateinit var trackDeliveryUseCase: TrackDeliveryUseCase

    @BeforeEach
    fun setUp() {
        registry = mock()
        trackDeliveryUseCase = mock()
        sut = DeliverySseController(registry, trackDeliveryUseCase)
    }

    private fun snapshotOf(deliveryId: UUID) = DeliveryStatusEvent(
        deliveryId = deliveryId,
        status = DeliveryStatus.PREPARING.name,
        changedAt = Instant.parse("2026-08-21T00:00:00Z"),
    )

    // ═══════════════════════════════════════════
    // 정책 1: 구독 우선 — 조회보다 register 가 먼저 일어난다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 스냅샷을 읽기 전에 구독이 먼저 열려야 한다")
    inner class SubscribeBeforeSnapshotPolicy {

        @Test
        @DisplayName("register 가 스냅샷 조회보다 먼저 호출된다")
        fun track_always_registersBeforeReadingSnapshot() {
            val deliveryId = UUID.randomUUID()
            whenever(trackDeliveryUseCase.execute(deliveryId)).thenReturn(snapshotOf(deliveryId))

            sut.track(deliveryId)

            inOrder(registry, trackDeliveryUseCase) {
                verify(registry).register(eq(deliveryId), any())
                verify(trackDeliveryUseCase).execute(deliveryId)
            }
        }
    }

    // ═══════════════════════════════════════════
    // 정책 2: 실패 시 되돌리기 — 등록만 남는 유령 구독을 막는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 스냅샷 단계가 실패하면 등록을 되돌려야 한다")
    inner class RollbackOnSnapshotFailurePolicy {

        @Test
        @DisplayName("없는 배송이면 등록을 제거하고 예외를 그대로 올린다")
        fun track_unknownDelivery_removesRegistrationAndRethrows() {
            val deliveryId = UUID.randomUUID()
            whenever(trackDeliveryUseCase.execute(deliveryId))
                .doThrow(DeliveryNotFoundException(deliveryId))

            assertFailsWith<DeliveryNotFoundException> { sut.track(deliveryId) }

            verify(registry).remove(eq(deliveryId), any())
        }

        @Test
        @DisplayName("정상 조회면 등록을 되돌리지 않는다")
        fun track_knownDelivery_keepsRegistration() {
            val deliveryId = UUID.randomUUID()
            whenever(trackDeliveryUseCase.execute(deliveryId)).thenReturn(snapshotOf(deliveryId))

            sut.track(deliveryId)

            verify(registry, never()).remove(eq(deliveryId), any<SseEmitter>())
        }
    }
}
