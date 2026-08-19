package com.logcat.tracking.integration.delivery

import com.logcat.tracking.external.messaging.kafka.delivery.dto.DeliveryStatusChangedEvent
import com.logcat.tracking.external.messaging.redis.DeliveryStatusRedisPublisher
import com.logcat.tracking.external.web.api.sse.SseEmitterRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

// Redis Pub/Sub 왕복만 검증한다.
// "인스턴스 A 가 받은 이벤트가 인스턴스 B 의 연결에 도달한다" 는 단일 JVM 에서 재현할 수 없다.
// 컨텍스트를 둘 띄워 각각의 SseEmitterRegistry 를 구분해야 하는데, 그렇게 만든 테스트는
// 복잡해서 아무도 안 고친다. 교차 도달은 ch4 §9.3 수동 절차로 남긴다.
@SpringBootTest
@Testcontainers
@DisplayName("Redis Pub/Sub fan-out: 채널 왕복")
class DeliveryStatusRedisFanoutIntegrationTest {

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
        val redis = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

        @Container
        @JvmStatic
        val kafka = ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))

        // 이게 없으면 로컬 docker-compose Redis 를 향한다. 개발 중 띄워 둔 앱의 구독자가
        // 같은 채널에 붙어 있어 결과가 오염된다.
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

    @Autowired lateinit var sut: DeliveryStatusRedisPublisher
    @Autowired lateinit var sseEmitterRegistry: SseEmitterRegistry
    @Autowired lateinit var stringRedisTemplate: StringRedisTemplate

    private fun event(deliveryId: UUID, status: String) =
        DeliveryStatusChangedEvent(deliveryId, status, Instant.now())

    // ═══════════════════════════════════════════
    // 정책 1: 채널 왕복 — 발행한 이벤트가 구독자를 거쳐 emitter 에 닿는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 채널로 발행한 이벤트가 같은 프로세스의 구독자를 거쳐 emitter 에 도달해야 한다")
    inner class ChannelRoundTripPolicy {

        @Test
        @DisplayName("등록된 emitter 가 있으면 발행 후 send 가 호출된다")
        fun publish_registeredEmitter_reachesEmitter() {
            val deliveryId = UUID.randomUUID()
            val emitter = mock<SseEmitter>()
            sseEmitterRegistry.register(deliveryId, emitter)

            sut.publish(event(deliveryId, "PICKED_UP"))

            // Pub/Sub 은 비동기다. 고정 sleep 대신 timeout 을 쓴다 —
            // 조건이 만족되면 즉시 통과하므로 더 빠르고 덜 흔들린다.
            verify(emitter, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
        }

        @Test
        @DisplayName("같은 배송에 두 연결이 붙어 있으면 둘 다 받는다")
        fun publish_twoEmittersOnSameDelivery_bothReceive() {
            val deliveryId = UUID.randomUUID()
            val first = mock<SseEmitter>()
            val second = mock<SseEmitter>()
            sseEmitterRegistry.register(deliveryId, first)
            sseEmitterRegistry.register(deliveryId, second)

            sut.publish(event(deliveryId, "DELIVERING"))

            verify(first, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
            verify(second, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
        }

        @Test
        @DisplayName("다른 배송의 연결로는 가지 않는다")
        fun publish_otherDelivery_doesNotReach() {
            val target = UUID.randomUUID()
            val other = UUID.randomUUID()
            val targetEmitter = mock<SseEmitter>()
            val otherEmitter = mock<SseEmitter>()
            sseEmitterRegistry.register(target, targetEmitter)
            sseEmitterRegistry.register(other, otherEmitter)

            sut.publish(event(target, "ARRIVED"))

            verify(targetEmitter, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
            verify(otherEmitter, never()).send(any<SseEmitter.SseEventBuilder>())
        }
    }

    // ═══════════════════════════════════════════
    // 정책 2: 구독자는 어떤 메시지에도 죽지 않는다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: 처리할 수 없는 메시지가 들어와도 구독자가 살아 있어야 한다")
    inner class SubscriberResiliencePolicy {

        @Test
        @DisplayName("연결이 없는 배송으로 발행해도 이후 정상 메시지가 도달한다")
        fun publish_noSubscriberThenNormal_stillReaches() {
            // 아무도 등록하지 않은 배송. 여기서 NPE 가 나면 이벤트를 받은 인스턴스 중
            // 연결이 없는 쪽이 전부 터진다 — 다중 인스턴스에서는 그쪽이 다수다.
            sut.publish(event(UUID.randomUUID(), "DELIVERING"))

            val deliveryId = UUID.randomUUID()
            val emitter = mock<SseEmitter>()
            sseEmitterRegistry.register(deliveryId, emitter)
            sut.publish(event(deliveryId, "PICKED_UP"))

            verify(emitter, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
        }

        @Test
        @DisplayName("깨진 메시지가 채널에 들어와도 이후 정상 메시지가 도달한다")
        fun publish_malformedThenNormal_stillReaches() {
            val deliveryId = UUID.randomUUID()
            val emitter = mock<SseEmitter>()
            sseEmitterRegistry.register(deliveryId, emitter)

            // 구독자가 역직렬화하지 못하는 payload 를 채널에 직접 던진다.
            stringRedisTemplate.convertAndSend(DeliveryStatusRedisPublisher.CHANNEL, "not-a-json")
            Thread.sleep(500)

            sut.publish(event(deliveryId, "ARRIVED"))

            // 도달하지 않으면 구독자의 try/catch 가 빠졌거나 예외를 밖으로 던지고 있다.
            verify(emitter, timeout(5_000)).send(any<SseEmitter.SseEventBuilder>())
        }
    }
}
