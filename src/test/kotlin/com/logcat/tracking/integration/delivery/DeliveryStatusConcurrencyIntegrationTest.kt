package com.logcat.tracking.integration.delivery

import com.logcat.tracking.application.delivery.usecase.UpdateDeliveryStatusUseCase
import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.delivery.port.DeliveryQueryPort
import com.logcat.tracking.core.delivery.service.DeliveryTrackingNumberGenerator
import com.logcat.tracking.integration.SharedContainers
import com.logcat.tracking.jooq.generated.tables.references.DELIVERY_STATUS_HISTORY
import org.jooq.DSLContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@DisplayName("UpdateDeliveryStatusUseCase: 동시 상태 변경")
class DeliveryStatusConcurrencyIntegrationTest {

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) = SharedContainers.bind(registry)
    }

    @Autowired lateinit var updateStatusUseCase: UpdateDeliveryStatusUseCase
    @Autowired lateinit var deliveryCommandPort: DeliveryCommandPort
    @Autowired lateinit var deliveryQueryPort: DeliveryQueryPort
    @Autowired lateinit var idGenerator: IdGenerator
    @Autowired lateinit var dsl: DSLContext

    private fun deliveryAt(status: DeliveryStatus): UUID {
        val deliveryId = idGenerator.nextId()
        deliveryCommandPort.save(
            Delivery.create(
                id = deliveryId,
                productId = UUID.randomUUID(),
                orderId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                trackingNumber = DeliveryTrackingNumberGenerator.generate(),
                address = "동시성 검증용",
                now = Instant.now(),
            ),
        )
        // ORDER_RECEIVED 에서 목표 상태까지 정규 경로로 올린다.
        val path = listOf(
            DeliveryStatus.PREPARING,
            DeliveryStatus.READY_FOR_PICKUP,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.DELIVERING,
        )
        for (step in path) {
            updateStatusUseCase.execute(deliveryId, step)
            if (step == status) break
        }
        return deliveryId
    }

    // ═══════════════════════════════════════════
    // 정책 1: 같은 출발 상태에서 갈라지는 두 전이는 하나만 성공해야 한다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 같은 상태에서 출발한 동시 전이는 하나만 성공해야 한다")
    inner class SingleWinnerPolicy {

        @Test
        @DisplayName("READY_FOR_PICKUP 에서 PICKED_UP 과 CANCELLED 가 동시에 오면 하나만 반영된다")
        fun execute_concurrentDivergentTransitions_onlyOneSucceeds() {
            val deliveryId = deliveryAt(DeliveryStatus.READY_FOR_PICKUP)

            // 전이 규칙상 READY_FOR_PICKUP 에서 둘 다 유효하다.
            // 규칙만으로는 못 막고, 갱신 조건이 막아야 한다.
            val targets = listOf(DeliveryStatus.PICKED_UP, DeliveryStatus.CANCELLED)
            val ready = CountDownLatch(targets.size)
            val start = CountDownLatch(1)
            val done = CountDownLatch(targets.size)
            val succeeded = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(targets.size)

            targets.forEach { target ->
                pool.submit {
                    ready.countDown()
                    start.await()
                    runCatching { updateStatusUseCase.execute(deliveryId, target) }
                        .onSuccess { succeeded.incrementAndGet() }
                    done.countDown()
                }
            }

            ready.await(10, TimeUnit.SECONDS)
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "두 요청이 끝나야 한다")
            pool.shutdown()

            assertEquals(1, succeeded.get(), "정확히 하나만 성공해야 한다")

            // 진 쪽이 롤백됐는지 이력으로 확인한다.
            // 갱신만 막고 이력이 남으면 나중에 어느 쪽이 진짜인지 알 수 없다.
            val finalStatus = deliveryQueryPort.findById(deliveryId)?.status
            val historyCount = dsl.selectCount()
                .from(DELIVERY_STATUS_HISTORY)
                .where(
                    DELIVERY_STATUS_HISTORY.DELIVERY_ID.eq(deliveryId)
                        .and(DELIVERY_STATUS_HISTORY.FROM_STATUS.eq(DeliveryStatus.READY_FOR_PICKUP.name)),
                )
                .fetchOne(0, Int::class.java) ?: 0

            assertTrue(
                finalStatus == DeliveryStatus.PICKED_UP || finalStatus == DeliveryStatus.CANCELLED,
                "둘 중 하나여야 한다. 실제 = $finalStatus",
            )
            assertEquals(1, historyCount, "READY_FOR_PICKUP 에서 나간 이력은 1건이어야 한다")
        }

        @Test
        @DisplayName("같은 전이가 동시에 두 번 오면 한 번만 반영된다")
        fun execute_concurrentSameTransition_appliedOnce() {
            val deliveryId = deliveryAt(DeliveryStatus.PICKED_UP)

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val succeeded = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(2)

            repeat(2) {
                pool.submit {
                    start.await()
                    runCatching { updateStatusUseCase.execute(deliveryId, DeliveryStatus.DELIVERING) }
                        .onSuccess { succeeded.incrementAndGet() }
                    done.countDown()
                }
            }

            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))
            pool.shutdown()

            assertEquals(1, succeeded.get(), "중복 전이는 한 번만 성공해야 한다")
            assertEquals(DeliveryStatus.DELIVERING, deliveryQueryPort.findById(deliveryId)?.status)
        }
    }

    // ═══════════════════════════════════════════
    // 정책 2: 터미널 이후 전이는 규칙 자체가 막는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 픽업 이후에는 취소가 성립하지 않아야 한다")
    inner class NoCancelAfterPickupPolicy {

        @Test
        @DisplayName("PICKED_UP 상태에서 CANCELLED 요청은 거부된다")
        fun execute_cancelAfterPickup_rejected() {
            val deliveryId = deliveryAt(DeliveryStatus.PICKED_UP)

            val result = runCatching { updateStatusUseCase.execute(deliveryId, DeliveryStatus.CANCELLED) }

            assertTrue(result.isFailure, "픽업 이후 취소는 거부돼야 한다")
            assertEquals(DeliveryStatus.PICKED_UP, deliveryQueryPort.findById(deliveryId)?.status)
        }
    }
}
