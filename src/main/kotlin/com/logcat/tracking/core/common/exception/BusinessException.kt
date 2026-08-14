package com.logcat.tracking.core.common.exception

abstract class BusinessException(
    val errorCode: String,
    override val message: String, // nullable 하지 않게 타입 강제
    val httpStatus: Int = 400,
    cause: Throwable? = null
): RuntimeException(message, cause)
