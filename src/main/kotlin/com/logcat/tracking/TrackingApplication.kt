package com.logcat.tracking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class TrackingApplication

fun main(args: Array<String>) {
    runApplication<TrackingApplication>(*args)
}
