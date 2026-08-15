plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"

    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"

    id("nu.studer.jooq") version "9.0"
}

group = "com.logcat.tracking"
version = "0.0.1-SNAPSHOT"
description = "realtime-delivery-tracking"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["jooqVersion"] = "3.19.29"
val jooqVersion: String by extra

fun env(key: String, default: String): String =
    System.getenv(key)
        ?: project.findProperty(key)?.toString()
        ?: default

// jOOQ codegen 은 Flyway 가 올려둔 실제 스키마를 읽는다.
// Flyway Gradle 플러그인은 붙이지 않았다(런타임 spring-boot-starter-flyway 만 있다).
// 그래서 flywayMigrate 태스크가 없다.
//   최초 1회(스키마가 아예 없을 때): psql 로 직접 넣는다 — 앱이 컴파일되기 전이라 다른 방법이 없다.
//   그 이후: 앱을 한 번 띄워 Flyway 가 적용하게 한 뒤 ./gradlew generateJooq.
//   ⚠ 이미 baseline 이 잡힌 뒤 psql 로 테이블을 먼저 만들면
//     Flyway 가 같은 버전을 다시 실행하려다 "relation already exists" 로 기동이 죽는다.
val dbUrl = env("DB_URL", "jdbc:postgresql://localhost:15433/tracking")
val dbUsername = env("DB_USERNAME", "tracking")
val dbPassword = env("DB_PASSWORD", "tracking")
val dbDriver = "org.postgresql.Driver"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq-kotlin:$jooqVersion")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // jOOQ codegen
    jooqGenerator("org.jooq:jooq-meta-extensions:$jooqVersion")
    jooqGenerator(project(":custom-strategy"))
    jooqGenerator("org.postgresql:postgresql")

    // Flyway
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Redis — Pub/Sub fan-out
//    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Kafka — Outbox 발행 대상
    implementation("org.springframework.kafka:spring-kafka")

    // UUID v7 (시간 정렬 가능한 PK)
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // Jackson 3 (Spring Boot 4) — 패키지 루트가 tools.jackson.*
    // com.fasterxml.* 는 Jackson 2 이므로 섞지 않는다.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("com.redis:testcontainers-redis")
}

jooq {
    version.set(jooqVersion)

    configurations {
        create("main").apply {
            generateSchemaSourceOnCompilation.set(false)

            jooqConfiguration.apply {
                jdbc.apply {
                    driver = dbDriver
                    url = dbUrl
                    user = dbUsername
                    password = dbPassword
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"

                    strategy.apply {
                        name = "org.dispatch.kotlinjooq.PrefixGeneratorStrategy"
                    }

                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        includes = ".*"
                        excludes = "flyway_schema_history"
                    }

                    generate.apply {
                        isRecords = true
                        isFluentSetters = true
                        isJavaTimeTypes = true
                        isDeprecated = false
                    }

                    target.apply {
                        packageName = "com.logcat.tracking.jooq.generated"
                        directory = "src/main/generated"
                    }
                }
            }
        }
    }
}

// jOOQ 생성 코드를 소스셋에 추가
sourceSets {
    main {
        kotlin {
            srcDir("src/main/generated")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)

        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
