package com.logcat.tracking.core.common.port

import java.util.*

interface IdGenerator {
    fun nextId(): UUID
}
