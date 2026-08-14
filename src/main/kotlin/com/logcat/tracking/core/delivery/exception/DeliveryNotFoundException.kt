package com.logcat.tracking.core.delivery.exception

import com.logcat.tracking.core.common.exception.BusinessException
import java.util.*

class DeliveryNotFoundException(id: UUID) : BusinessException(
    errorCode = "DELIVERY_NOT_FOUND",
    httpStatus = 404,
    message = "Delivery with id $id not found",
)
