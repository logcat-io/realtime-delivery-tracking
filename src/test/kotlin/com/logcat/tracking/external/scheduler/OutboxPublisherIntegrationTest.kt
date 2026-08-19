package com.logcat.tracking.external.scheduler

import com.logcat.tracking.application.delivery.usecase.UpdateDeliveryStatusUseCase
import com.logcat.tracking.core.common.port.IdGenerator
import com.logcat.tracking.core.delivery.model.Delivery
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.core.delivery.port.DeliveryCommandPort
import com.logcat.tracking.core.delivery.port.DeliveryQueryPort
import com.logcat.tracking.core.delivery.service.DeliveryTrackingNumberGenerator
import com.logcat.tracking.external.persistence.jooq.outbox.OutboxEventJooqAdapter
import com.logcat.tracking.jooq.generated.tables.references.DELIVERIES
import com.logcat.tracking.jooq.generated.tables.references.DELIVERY_STATUS_HISTORY
import com.logcat.tracking.jooq.generated.tables.references.OUTBOX_EVENTS
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
class OutboxPublisherIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("tracking_test")
            withUsername("test")
            withPassword("test")
        }

        @Container
        @JvmStatic
        val kafka = ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"),
        )

        // Redis 구독자가 컨텍스트에 들어온 뒤로 이 테스트도 Redis 없이는 기동하지 못한다.
        // outbox 검증에 Redis 를 쓰지는 않지만 컨텍스트가 요구한다.
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

        // 컨테이너는 매 실행마다 랜덤 호스트 포트에 바인딩된다.
        // application.yml 의 고정 포트(15433/19092)를 그대로 쓰면 로컬 docker-compose 를
        // 향하게 되어, 초록불은 뜨는데 검증 대상이 컨테이너가 아니게 된다.
        // 최악은 @BeforeEach 의 DELETE 가 개발 DB 를 비우는 것이다.
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Autowired lateinit var outboxAdapter: OutboxEventJooqAdapter
    @Autowired lateinit var publisher: OutboxPublisher
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var updateStatusUseCase: UpdateDeliveryStatusUseCase
    @Autowired lateinit var deliveryCommandPort: DeliveryCommandPort
    @Autowired lateinit var deliveryQueryPort: DeliveryQueryPort
    @Autowired lateinit var idGenerator: IdGenerator
    @Autowired lateinit var txTemplate: TransactionTemplate

    @BeforeEach
    fun cleanup() {
        // 조건 없는 DELETE 를 막는 jOOQ 정책을 피하려고 trueCondition 을 명시한다.
        dsl.deleteFrom(OUTBOX_EVENTS).where(DSL.trueCondition()).execute()
        dsl.deleteFrom(DELIVERY_STATUS_HISTORY).where(DSL.trueCondition()).execute()
        dsl.deleteFrom(DELIVERIES).where(DSL.trueCondition()).execute()
    }

    @Test
    fun `PENDING 이벤트 1건이 Kafka 로 발행되고 PUBLISHED 로 마킹된다`() {
        val aggregateId = UUID.randomUUID()
        val payload =
            """{"deliveryId":"$aggregateId","status":"PICKED_UP","changedAt":"2026-04-30T12:00:00Z"}"""

        outboxAdapter.saveEvent(
            aggregateType = "DELIVERY",
            aggregateId = aggregateId,
            eventType = "DELIVERY_STATUS_CHANGED",
            payload = payload,
        )

        publisher.publishPendingEvents()

        val row = dsl.select(OUTBOX_EVENTS.STATUS, OUTBOX_EVENTS.PUBLISHED_AT)
            .from(OUTBOX_EVENTS)
            .where(OUTBOX_EVENTS.AGGREGATE_ID.eq(aggregateId))
            .fetchOne()
        assertNotNull(row, "outbox 행이 존재해야 한다")
        assertEquals("PUBLISHED", row[OUTBOX_EVENTS.STATUS])
        assertNotNull(row[OUTBOX_EVENTS.PUBLISHED_AT], "published_at 이 기록되어야 한다")

        // PG 의 JSONB 는 키 순서를 재정렬한다. 문자열 동등 비교를 하면 재현 불가능한 실패가 난다.
        val received = consumeByKey(OutboxPublisher.TOPIC, aggregateId, Duration.ofSeconds(15))
        assertNotNull(received, "Kafka 메시지가 수신되어야 한다")
        assertTrue(received.contains(aggregateId.toString()))
        assertTrue(received.contains("PICKED_UP"))
        assertTrue(received.contains("2026-04-30T12:00:00Z"))
    }

    @Test
    fun `메시지 키가 aggregateId 로 발행된다`() {
        val aggregateId = UUID.randomUUID()
        outboxAdapter.saveEvent(
            aggregateType = "DELIVERY",
            aggregateId = aggregateId,
            eventType = "DELIVERY_STATUS_CHANGED",
            payload = """{"deliveryId":"$aggregateId","status":"DELIVERING"}""",
        )

        publisher.publishPendingEvents()

        // consumeByKey 는 record.key() == aggregateId 인 레코드만 반환한다.
        // non-null 이 나온 것 자체가 "키가 aggregateId 로 찍혔다"의 증명이다.
        val received = consumeByKey(OutboxPublisher.TOPIC, aggregateId, Duration.ofSeconds(15))
        assertNotNull(received, "aggregateId 를 키로 갖는 메시지가 있어야 한다")
    }

    @Test
    fun `이미 PUBLISHED 인 이벤트는 재발행되지 않는다`() {
        val aggregateId = UUID.randomUUID()
        outboxAdapter.saveEvent(
            aggregateType = "DELIVERY",
            aggregateId = aggregateId,
            eventType = "DELIVERY_STATUS_CHANGED",
            payload = """{"deliveryId":"$aggregateId","status":"ARRIVED"}""",
        )
        publisher.publishPendingEvents()

        val first = dsl.select(OUTBOX_EVENTS.PUBLISHED_AT)
            .from(OUTBOX_EVENTS).where(OUTBOX_EVENTS.AGGREGATE_ID.eq(aggregateId))
            .fetchOne(OUTBOX_EVENTS.PUBLISHED_AT)
        assertNotNull(first, "첫 발행 후 published_at 이 있어야 한다")

        // findPendingEvents 는 status='PENDING' 으로 거르므로 이 행은 후보에 안 든다.
        publisher.publishPendingEvents()

        val second = dsl.select(OUTBOX_EVENTS.PUBLISHED_AT)
            .from(OUTBOX_EVENTS).where(OUTBOX_EVENTS.AGGREGATE_ID.eq(aggregateId))
            .fetchOne(OUTBOX_EVENTS.PUBLISHED_AT)
        assertEquals(first, second, "PUBLISHED 이벤트는 재처리되지 않아야 한다")
    }

    @Test
    fun `빈 outbox 에서 publish 를 호출해도 아무 일도 일어나지 않는다`() {
        publisher.publishPendingEvents()

        val count = dsl.selectCount().from(OUTBOX_EVENTS).fetchOne(0, Int::class.java) ?: 0
        assertEquals(0, count)
    }


    // ── 유령 이벤트 재현을 그대로 반복한다. 이번엔 0 이 나와야 한다 ──
    //
    // 직접 발행:  트랜잭션이 롤백돼도 이벤트는 이미 브로커에 나가 있었다 → 유령
    // outbox:    롤백되면 행 자체가 사라진다 → 발행될 이벤트가 없다 → 유령 구조적 불가
    @Test
    fun `롤백되면 outbox 행이 남지 않아 발행될 이벤트가 없다`() {
        val deliveryId = idGenerator.nextId()
        val now = Instant.now()

        txTemplate.executeWithoutResult {
            deliveryCommandPort.save(
                Delivery.create(
                    id = deliveryId,
                    productId = UUID.randomUUID(),
                    orderId = UUID.randomUUID(),
                    userId = UUID.randomUUID(),
                    trackingNumber = DeliveryTrackingNumberGenerator.generate(),
                    address = "유령 검증용",
                    now = now,
                ),
            )
        }

        val before = countOutbox()

        // 상태 변경을 한 뒤 같은 트랜잭션에서 예외를 던진다.
        // UpdateDeliveryStatusUseCase 는 REQUIRED 라 이 트랜잭션에 합류하므로,
        // outbox INSERT 도 함께 롤백 대상이 된다.
        runCatching {
            txTemplate.executeWithoutResult {
                updateStatusUseCase.execute(deliveryId, DeliveryStatus.PREPARING)
                error("forced rollback for ghost verification")
            }
        }

        assertEquals(before, countOutbox(), "롤백되면 outbox 행도 같이 사라져야 한다")
        assertEquals(
            DeliveryStatus.ORDER_RECEIVED,
            deliveryQueryPort.findById(deliveryId)?.status,
            "상태 변경도 롤백되어야 한다",
        )

        // 발행할 이벤트가 없으므로 워커를 돌려도 아무 일이 없다.
        publisher.publishPendingEvents()
        assertEquals(before, countOutbox())
    }

    private fun countOutbox(): Int =
        dsl.selectCount().from(OUTBOX_EVENTS).fetchOne(0, Int::class.java) ?: 0

    // 토픽은 테스트끼리 공유되므로 키로 걸러 격리한다.
    // group.id 를 매번 랜덤으로 만들어 offset 잔여가 다음 테스트에 영향 주는 걸 막는다.
    private fun consumeByKey(topic: String, key: UUID, timeout: Duration): String? {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "outbox-test-${UUID.randomUUID()}",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        )
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500))
                    .firstOrNull { it.key() == key.toString() }
                    ?.let { return it.value() }
            }
        }
        return null
    }
}
