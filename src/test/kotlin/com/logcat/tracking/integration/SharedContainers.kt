package com.logcat.tracking.integration

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

object SharedContainers {

    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").apply {
        withDatabaseName("tracking_test")
        withUsername("test")
        withPassword("test")
    }

    val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").apply {
        withExposedPorts(6379)
    }

    val kafka: ConfluentKafkaContainer =
        ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))

    init {
        postgres.start()
        redis.start()
        kafka.start()
    }

    fun bind(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { postgres.jdbcUrl }
        registry.add("spring.datasource.username") { postgres.username }
        registry.add("spring.datasource.password") { postgres.password }
        registry.add("spring.data.redis.host") { redis.host }
        registry.add("spring.data.redis.port") { redis.firstMappedPort }
        registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
}
