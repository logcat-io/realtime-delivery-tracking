package com.logcat.tracking.core.delivery.service

import java.time.LocalDate
import java.util.*

object DeliveryTrackingNumberGenerator {
    // test 를 위해서 today 를 파라미터 + 기본값으로 준다.
    fun generate(today: LocalDate = LocalDate.now()): String {
        val datePart = today.toString().replace("-", "")
        val suffix = UUID.randomUUID().toString().substring(0, 6).uppercase()
        return "DLV-$datePart-$suffix"
    }
}
