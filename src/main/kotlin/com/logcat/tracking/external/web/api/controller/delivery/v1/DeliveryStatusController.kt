package com.logcat.tracking.external.web.api.controller.delivery.v1

import com.logcat.tracking.application.delivery.usecase.UpdateDeliveryStatusUseCase
import com.logcat.tracking.core.delivery.model.DeliveryStatus
import com.logcat.tracking.external.web.api.common.response.ApiResponse
import com.logcat.tracking.external.web.api.controller.delivery.v1.request.ChangeDeliveryStatusRequest
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryStatusController(
    private val updateUpdateDeliveryStatusUseCase: UpdateDeliveryStatusUseCase,
) {

    @PutMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestBody request: ChangeDeliveryStatusRequest,
    ): ApiResponse<Nothing> {
        updateUpdateDeliveryStatusUseCase.execute(
            deliveryId = id,
            target = DeliveryStatus.valueOf(request.status),
            reason = request.reason,
        )

        return ApiResponse.success(null)
    }
}
