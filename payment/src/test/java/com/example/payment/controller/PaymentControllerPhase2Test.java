package com.example.payment.controller;

import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.service.PaymentResult;
import com.example.payment.service.PaymentService;
import com.example.payment.service.PaymentStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Cycle 8: PaymentController Phase 2 확장 테스트.
 *
 * Phase 2에서 추가된 동작:
 * 1. UNKNOWN 상태 결제 → HTTP 202 Accepted (클라이언트에게 폴링 필요함을 알림)
 * 2. GET /{orderNumber}/status 엔드포인트 — UNKNOWN 상태 클라이언트 폴링용
 * 3. 존재하지 않는 주문 조회 → 404
 *
 * @WebMvcTest: Controller 레이어만 슬라이싱하여 빠르게 HTTP 시멘틱 검증
 *              MockMvcTester: Spring Boot 4의 AssertJ 스타일 MockMvc (MockMvc 대체)
 * @MockitoBean: Spring Boot 4에서 @MockBean 대체
 */
@WebMvcTest(PaymentController.class)
@DisplayName("PaymentController - Phase 2 확장 테스트")
class PaymentControllerPhase2Test {

        @Autowired
        private MockMvcTester mvc;

        @MockitoBean
        private PaymentService paymentService;

        @Test
        @DisplayName("POST /api/payments - PG 성공 시 HTTP 200 + status=SUCCESS")
        void whenSuccess_thenHttp200() {
                // given
                given(paymentService.processPayment("ORD-001"))
                                .willReturn(new PaymentResult("ORD-001", "SUCCESS", "pg-key-001"));

                // when / then
                assertThat(mvc.post().uri("/api/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderNumber\": \"ORD-001\"}"))
                                .hasStatusOk()
                                .bodyJson()
                                .convertTo(PaymentResult.class)
                                .satisfies(result -> {
                                        assertThat(result.status()).isEqualTo("SUCCESS");
                                        assertThat(result.paymentKey()).isEqualTo("pg-key-001");
                                });
        }

        @Test
        @DisplayName("POST /api/payments - PG 실패 시 HTTP 200 + status=FAILED")
        void whenFailed_thenHttp200() {
                // given
                given(paymentService.processPayment("ORD-001"))
                                .willReturn(new PaymentResult("ORD-001", "FAILED", null));

                // when / then
                assertThat(mvc.post().uri("/api/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderNumber\": \"ORD-001\"}"))
                                .hasStatusOk()
                                .bodyJson()
                                .convertTo(PaymentResult.class)
                                .satisfies(result -> assertThat(result.status()).isEqualTo("FAILED"));
        }

        @Test
        @DisplayName("POST /api/payments - UNKNOWN 시 HTTP 202 Accepted 반환")
        void whenUnknown_thenHttp202() {
                // given
                // UNKNOWN은 예외가 아닌 결과값으로 반환된다 (Phase 2 설계 원칙)
                // HTTP 202로 구분하여 클라이언트가 GET /status 폴링으로 최종 상태를 확인해야 함을 알린다
                given(paymentService.processPayment("ORD-001"))
                                .willReturn(new PaymentResult("ORD-001", "UNKNOWN", null));

                // when / then
                assertThat(mvc.post().uri("/api/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderNumber\": \"ORD-001\"}"))
                                .hasStatus(HttpStatus.ACCEPTED) // 202
                                .bodyJson()
                                .convertTo(PaymentResult.class)
                                .satisfies(result -> assertThat(result.status()).isEqualTo("UNKNOWN"));
        }

        @Test
        @DisplayName("GET /api/payments/ORD-001/status - 정상 조회 시 HTTP 200 + PaymentStatusResponse")
        void whenGetStatus_thenHttp200() {
                // given
                given(paymentService.getPaymentStatus("ORD-001"))
                                .willReturn(new PaymentStatusResponse(
                                                "ORD-001",
                                                OrderStatus.SUCCESS,
                                                PaymentStatus.SUCCESS,
                                                "pg-key-001"));

                // when / then
                assertThat(mvc.get().uri("/api/payments/ORD-001/status"))
                                .hasStatusOk()
                                .bodyJson()
                                .convertTo(PaymentStatusResponse.class)
                                .satisfies(response -> {
                                        assertThat(response.orderNumber()).isEqualTo("ORD-001");
                                        assertThat(response.orderStatus()).isEqualTo(OrderStatus.SUCCESS);
                                        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
                                        assertThat(response.pgPaymentKey()).isEqualTo("pg-key-001");
                                });
        }

        @Test
        @DisplayName("GET /api/payments/NOT-EXIST/status - 존재하지 않는 주문 → HTTP 404")
        void whenGetStatusNotFound_thenHttp404() {
                // given
                // Controller에서 IllegalArgumentException을 잡아 404로 매핑해야 한다
                given(paymentService.getPaymentStatus("NOT-EXIST"))
                                .willThrow(new IllegalArgumentException("주문을 찾을 수 없습니다: NOT-EXIST"));

                // when / then
                assertThat(mvc.get().uri("/api/payments/NOT-EXIST/status"))
                                .hasStatus(HttpStatus.NOT_FOUND);
        }
}
