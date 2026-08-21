package com.logcat.tracking.integration.delivery

import com.logcat.tracking.application.delivery.usecase.UpdateDeliveryStatusUseCase
import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.delivery.service.DeliveryTrackingNumberGenerator
import com.logcat.tracking.jooq.generated.tables.references.OUTBOX_EVENTS
import org.jooq.DSLContext
import com.logcat.tracking.integration.SharedContainers
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("DeliverySseController: 연결 시점 상태 스냅샷")
class DeliverySseSnapshotIntegrationTest {

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) = SharedContainers.bind(registry)
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var deliveryCommandPort: DeliveryCommandPort
    @Autowired lateinit var updateStatusUseCase: UpdateDeliveryStatusUseCase
    @Autowired lateinit var idGenerator: IdGenerator
    @Autowired lateinit var dsl: DSLContext

    private fun awaitOutboxDrained(timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pending = dsl.selectCount()
                .from(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.STATUS.eq("PENDING"))
                .fetchOne(0, Int::class.java) ?: 0
            if (pending == 0) {
                Thread.sleep(1_000)
                return
            }
            Thread.sleep(100)
        }
        error("outbox 가 비워지지 않았다")
    }

    private fun newDelivery(): UUID {
        val deliveryId = idGenerator.nextId()
        deliveryCommandPort.save(
            Delivery.create(
                id = deliveryId,
                productId = UUID.randomUUID(),
                orderId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                trackingNumber = DeliveryTrackingNumberGenerator.generate(),
                address = "스냅샷 검증용",
                now = Instant.now(),
            ),
        )
        return deliveryId
    }

    private data class Stream(val statusCode: Int, val body: String)

    private fun connect(deliveryId: UUID, readMillis: Int = 2000): Stream {
        val url = URI("http://localhost:$port/api/v1/deliveries/sse/$deliveryId/track").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Connection", "close")
            connectTimeout = 5000
            readTimeout = readMillis
        }

        val buffer = StringBuilder()
        return try {
            val code = conn.responseCode
            if (code < 400) {
                try {
                    conn.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            buffer.appendLine(line)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            Stream(code, buffer.toString())
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════
    // 정책 1: 연결 스냅샷 — 붙는 즉시 현재 상태가 한 번 나간다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 연결 직후 현재 상태가 스냅샷으로 전달되어야 한다")
    inner class ConnectionSnapshotPolicy {

        @Test
        @DisplayName("생성 직후 연결하면 ORDER_RECEIVED 스냅샷이 즉시 나간다")
        fun track_justCreatedDelivery_emitsCurrentStatusImmediately() {
            val deliveryId = newDelivery()

            val stream = connect(deliveryId)

            assertEquals(200, stream.statusCode)
            assertFalse(stream.body.isEmpty(), "스냅샷이 있으면 첫 프레임이 즉시 나가야 한다")
            assertTrue(stream.body.contains("status"), "status 이벤트로 나가야 한다")
            assertTrue(
                stream.body.contains(DeliveryStatus.ORDER_RECEIVED.name),
                "생성 직후 상태가 담겨야 한다. 실제 = ${stream.body}",
            )
            assertTrue(
                stream.body.contains(deliveryId.toString()),
                "대상 배송 id 가 담겨야 한다. 실제 = ${stream.body}",
            )
        }

        @Test
        @DisplayName("상태가 PREPARING 으로 바뀐 뒤 연결하면 PREPARING 이 나간다")
        fun track_afterStatusChanged_emitsChangedStatus() {
            val deliveryId = newDelivery()
            updateStatusUseCase.execute(deliveryId, DeliveryStatus.PREPARING)

            val stream = connect(deliveryId)

            assertTrue(
                stream.body.contains(DeliveryStatus.PREPARING.name),
                "바뀐 상태여야 한다. 실제 = ${stream.body}",
            )
            assertFalse(
                stream.body.contains(DeliveryStatus.ORDER_RECEIVED.name),
                "이전 상태가 남아 있으면 안 된다. 실제 = ${stream.body}",
            )
        }
    }

    // ═══════════════════════════════════════════
    // 정책 2: 끊긴 사이 메우기 — 재연결이 놓친 변경을 따라잡는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 끊겨 있는 동안 바뀐 상태를 재연결이 따라잡아야 한다")
    inner class ReconnectCatchUpPolicy {

        @Test
        @DisplayName("연결이 없는 사이 2번 바뀌어도 재연결은 마지막 상태 1건만 받는다")
        fun track_reconnectAfterMissedChanges_emitsOnlyLatestStatus() {
            val deliveryId = newDelivery()

            updateStatusUseCase.execute(deliveryId, DeliveryStatus.PREPARING)
            updateStatusUseCase.execute(deliveryId, DeliveryStatus.READY_FOR_PICKUP)
            awaitOutboxDrained()

            val stream = connect(deliveryId)

            assertTrue(
                stream.body.contains(DeliveryStatus.READY_FOR_PICKUP.name),
                "마지막 상태여야 한다. 실제 = ${stream.body}",
            )
            assertFalse(
                stream.body.contains(DeliveryStatus.PREPARING.name),
                "중간 과정은 재생하지 않는다. 실제 = ${stream.body}",
            )

            assertEquals(
                1,
                Regex("data:").findAll(stream.body).count(),
                "스냅샷은 1건이어야 한다. 실제 = ${stream.body}",
            )
        }
    }

    // ═══════════════════════════════════════════
    // 정책 3: 없는 배송 — 스트림을 열기 전에 끊는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 존재하지 않는 배송이면 스트림을 열지 않아야 한다")
    inner class UnknownDeliveryPolicy {

        @Test
        @DisplayName("없는 deliveryId 로 연결하면 스트림이 열리지 않는다")
        fun track_unknownDeliveryId_doesNotOpenStream() {
            val unknownId = UUID.randomUUID()

            val stream = connect(unknownId, readMillis = 1000)

            assertTrue(
                stream.statusCode >= 400,
                "없는 배송인데 스트림이 열리면 클라이언트가 영원히 기다린다. 실제 = ${stream.statusCode}",
            )
        }
    }
}
