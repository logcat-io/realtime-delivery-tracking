package com.logcat.tracking.external.web.api.common.exception

import com.logcat.tracking.application.delivery.usecase.UpdateDeliveryStatusUseCase
import com.logcat.tracking.core.delivery.exception.DeliveryNotFoundException
import com.logcat.tracking.external.web.api.controller.delivery.v1.DeliveryStatusController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

@DisplayName("GlobalExceptionHandler: 도메인 예외를 HTTP 응답으로 번역")
class GlobalExceptionHandlerTest {

    private lateinit var sut: GlobalExceptionHandler
    private lateinit var mockMvc: MockMvc
    private lateinit var updateDeliveryStatusUseCase: UpdateDeliveryStatusUseCase

    @BeforeEach
    fun setUp() {
        sut = GlobalExceptionHandler()
        updateDeliveryStatusUseCase = mock()
        mockMvc = MockMvcBuilders
            .standaloneSetup(DeliveryStatusController(updateDeliveryStatusUseCase))
            .setControllerAdvice(sut)
            .build()
    }

    private fun changeStatus(deliveryId: UUID, status: String) =
        put("/api/v1/deliveries/{id}/status", deliveryId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"status":"$status","reason":"test"}""")

    // ═══════════════════════════════════════════
    // 정책 1: 도메인 예외 번역 — BusinessException 이 지정한 상태코드로 나간다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: BusinessException 은 예외가 지정한 httpStatus 와 errorCode 로 응답해야 한다")
    inner class BusinessExceptionPolicy {

        @Test
        @DisplayName("DeliveryNotFoundException(httpStatus=404)이면 404와 DELIVERY_NOT_FOUND 를 응답한다")
        fun handleBusiness_deliveryNotFound_returns404WithErrorCode() {
            val deliveryId = UUID.randomUUID()
            doThrow(DeliveryNotFoundException(deliveryId))
                .whenever(updateDeliveryStatusUseCase).execute(any(), any(), anyOrNull())

            mockMvc.perform(changeStatus(deliveryId, "PREPARING"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Delivery with id $deliveryId not found"))
        }
    }

    // ═══════════════════════════════════════════
    // 정책 2: 불변식 위반 — require 로 던져진 IllegalArgumentException 은 400 이다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: IllegalArgumentException 은 500 이 아니라 400 으로 응답해야 한다")
    inner class IllegalArgumentPolicy {

        @Test
        @DisplayName("정의되지 않은 status 문자열이면 400과 BAD_REQUEST 를 응답한다")
        fun handleIllegalArgument_unknownStatusValue_returns400() {
            val deliveryId = UUID.randomUUID()

            mockMvc.perform(changeStatus(deliveryId, "NOT_A_STATUS"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
        }
    }

    // ═══════════════════════════════════════════
    // 정책 3: 상태 충돌 — 터미널 전이·CAS 실패의 IllegalStateException 은 409 다
    // ═══════════════════════════════════════════

    @Nested
    @DisplayName("정책: IllegalStateException 은 상태 충돌로 보고 409 로 응답해야 한다")
    inner class IllegalStatePolicy {

        @Test
        @DisplayName("터미널 상태에서 전이를 시도하면 409와 CONFLICT 를 응답한다")
        fun handleIllegalState_transitionFromTerminalStatus_returns409() {
            val deliveryId = UUID.randomUUID()
            doThrow(IllegalStateException("Terminal status: ARRIVED - no transition allowed"))
                .whenever(updateDeliveryStatusUseCase).execute(any(), any(), anyOrNull())

            mockMvc.perform(changeStatus(deliveryId, "DELIVERING"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
        }
    }
}
