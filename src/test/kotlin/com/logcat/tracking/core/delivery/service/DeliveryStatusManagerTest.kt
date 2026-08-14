package com.logcat.tracking.core.delivery.service

import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.model.DeliveryStatus.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// 프레임워크 의존 0 — JUnit + Kotlin 표준 라이브러리만.
// Spring Context 를 안 띄우므로 ms 단위로 끝난다.
class DeliveryStatusManagerTest {

    private val manager = DeliveryStatusManager()

    // ── 정상 라이프사이클 (ORDER_RECEIVED → ARRIVED) ──────────

    @Test
    fun `ORDER_RECEIVED → PREPARING`() {
        assertEquals(PREPARING, manager.transition(ORDER_RECEIVED, PREPARING))
    }

    @Test
    fun `PREPARING → READY_FOR_PICKUP`() {
        assertEquals(READY_FOR_PICKUP, manager.transition(PREPARING, READY_FOR_PICKUP))
    }

    @Test
    fun `READY_FOR_PICKUP → PICKED_UP`() {
        assertEquals(PICKED_UP, manager.transition(READY_FOR_PICKUP, PICKED_UP))
    }

    @Test
    fun `PICKED_UP → DELIVERING`() {
        assertEquals(DELIVERING, manager.transition(PICKED_UP, DELIVERING))
    }

    @Test
    fun `DELIVERING → ARRIVED`() {
        assertEquals(ARRIVED, manager.transition(DELIVERING, ARRIVED))
    }

    @Test
    fun `DELIVERING → FAILED (수취인 부재 등)`() {
        assertEquals(FAILED, manager.transition(DELIVERING, FAILED))
    }

    // ── 픽업 전 취소 가능 경로 ───────────────────────────────

    @Test
    fun `ORDER_RECEIVED → CANCELLED`() {
        assertEquals(CANCELLED, manager.transition(ORDER_RECEIVED, CANCELLED))
    }

    @Test
    fun `PREPARING → CANCELLED`() {
        assertEquals(CANCELLED, manager.transition(PREPARING, CANCELLED))
    }

    @Test
    fun `READY_FOR_PICKUP → CANCELLED`() {
        assertEquals(CANCELLED, manager.transition(READY_FOR_PICKUP, CANCELLED))
    }

    // ── 잘못된 전이 — IllegalArgumentException ──────────────

    @Test
    fun `PICKED_UP → CANCELLED 직접 전이 불가 — 픽업 후 취소 금지`() {
        assertFailsWith<IllegalArgumentException> {
            manager.transition(PICKED_UP, CANCELLED)
        }
    }

    @Test
    fun `ORDER_RECEIVED → ARRIVED 직접 전이 불가 — 중간 단계 필수`() {
        assertFailsWith<IllegalArgumentException> {
            manager.transition(ORDER_RECEIVED, ARRIVED)
        }
    }

    @Test
    fun `PREPARING → DELIVERING 직접 전이 불가 — 픽업 단계 건너뛰기 금지`() {
        assertFailsWith<IllegalArgumentException> {
            manager.transition(PREPARING, DELIVERING)
        }
    }

    @Test
    fun `DELIVERING → CANCELLED 불가 — 배송 중 취소 금지 (FAILED 만 허용)`() {
        assertFailsWith<IllegalArgumentException> {
            manager.transition(DELIVERING, CANCELLED)
        }
    }

    // ── 터미널 상태 — IllegalStateException ────────────────

    @Test
    fun `ARRIVED 에서는 어떤 전이도 불가`() {
        DeliveryStatus.entries.forEach { target ->
            assertFailsWith<IllegalStateException>("ARRIVED → $target") {
                manager.transition(ARRIVED, target)
            }
        }
    }

    @Test
    fun `CANCELLED 에서는 어떤 전이도 불가`() {
        DeliveryStatus.entries.forEach { target ->
            assertFailsWith<IllegalStateException>("CANCELLED → $target") {
                manager.transition(CANCELLED, target)
            }
        }
    }

    @Test
    fun `FAILED 에서는 어떤 전이도 불가`() {
        DeliveryStatus.entries.forEach { target ->
            assertFailsWith<IllegalStateException>("FAILED → $target") {
                manager.transition(FAILED, target)
            }
        }
    }
}
