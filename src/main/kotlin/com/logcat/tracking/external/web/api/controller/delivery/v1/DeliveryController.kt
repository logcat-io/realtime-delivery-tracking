package com.logcat.tracking.external.web.api.controller.delivery.v1

import com.logcat.tracking.application.delivery.usecase.CreateDeliveryUseCase
import com.logcat.tracking.external.web.api.common.response.ApiResponse
import com.logcat.tracking.external.web.api.controller.delivery.v1.request.CreateDeliveryRequest
import com.logcat.tracking.external.web.api.controller.delivery.v1.response.CreateDeliveryResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryController(
    private val createDeliveryUseCase: CreateDeliveryUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateDeliveryRequest): ApiResponse<CreateDeliveryResponse> {
        val result = createDeliveryUseCase.execute(request.toCommand())

        return ApiResponse.success(CreateDeliveryResponse.from(result))
    }
}
