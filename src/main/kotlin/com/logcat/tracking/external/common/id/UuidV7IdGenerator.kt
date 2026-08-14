package com.logcat.tracking.external.common.id

import com.fasterxml.uuid.Generators
import com.logcat.tracking.core.common.port.IdGenerator
import org.springframework.stereotype.Component
import java.util.*

@Component
class UuidV7IdGenerator : IdGenerator {

    private val generator = Generators.timeBasedEpochGenerator()

    override fun nextId(): UUID = generator.generate()
}
