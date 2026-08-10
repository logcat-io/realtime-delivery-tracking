package com.logcat.tracking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TrackingApplication

fun main(args: Array<String>) {
    runApplication<TrackingApplication>(*args)
}
