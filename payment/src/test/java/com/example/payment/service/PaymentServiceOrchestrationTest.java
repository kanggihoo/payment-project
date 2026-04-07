package com.example.payment.service;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.Order;
import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.wiremock.spring.EnableWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentService.processPayment() TX 분리 오케스트레이션 검증.
 *
 * Phase 2에서 해결하는 문제:
 * TX1(사전 커밋) → PG 호출(트랜잭션 밖) → TX2(결과 기록) 흐름이 올바르게 동작하는지 검증한다.
 * PG 응답 3가지 시나리오(성공/실패/타임아웃)에 따라 DB 상태가 올바르게 기록되어야 한다.
 *
 * 테스트 격리:
 * 각 테스트가 @BeforeEach에서 독립 주문을 생성하고 @AfterEach에서 정리한다.
 * processPayment()가 실제 커밋을 수행하므로 @Transactional 롤백 방식은 사용할 수 없다.
 * 시드 데이터를 공유하면 테스트 실행 순서에 따라 READY 상태가 보장되지 않아 실패한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableWireMock
@DisplayName("PaymentService - TX 분리 오케스트레이션 테스트")
class PaymentServiceOrchestrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    private Long testOrderId;
    private String testOrderNumber;

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", () -> "${wiremock.server.baseUrl}");
    }

    @BeforeEach
    void setUp() {
        // 각 테스트마다 유니크한 주문번호로 READY 상태 주문 생성
        testOrderNumber = "ORD-ORCH-TEST-" + System.nanoTime();
        Order order = orderRepository.save(new Order(testOrderNumber, 1000L));
        testOrderId = order.getId();
    }

    @AfterEach
    void tearDown() {
        paymentRepository.findByOrderIdOrderByIdDesc(testOrderId)
                .forEach(p -> paymentRepository.deleteById(p.getId()));
        orderRepository.deleteById(testOrderId);
    }

    @Test
    @DisplayName("PG 성공 시 → PaymentResult.status=SUCCESS, Order=SUCCESS, Payment=SUCCESS")
    void whenPgSuccess_thenStatusSuccess() {
        // given
        stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "pg-key-success",
                                  "orderId": "%s",
                                  "status": "DONE",
                                  "totalAmount": 1000
                                }
                                """.formatted(testOrderNumber))));

        // when
        PaymentResult result = paymentService.processPayment(testOrderNumber);

        // then
        assertThat(result.status()).isEqualTo("SUCCESS");

        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments).isNotEmpty();
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payments.get(0).getPgPaymentKey()).isEqualTo("pg-key-success");
    }

    @Test
    @DisplayName("PG 4xx 실패 시 → PaymentResult.status=FAILED, Order=FAILED, Payment=FAILED")
    void whenPgFails_thenStatusFailed() {
        // given
        stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"잔액 부족\"}")));

        // when
        PaymentResult result = paymentService.processPayment(testOrderNumber);

        // then
        assertThat(result.status()).isEqualTo("FAILED");

        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments).isNotEmpty();
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("PG 네트워크 단절(Fault) 시 → PaymentResult.status=UNKNOWN, Order=PENDING 유지, Payment=UNKNOWN")
    void whenPgTimeout_thenStatusUnknown() {
        // given
        stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        // when — UNKNOWN은 예외가 아닌 결과값으로 반환되어야 한다
        PaymentResult result = paymentService.processPayment(testOrderNumber);

        // then
        assertThat(result.status()).isEqualTo("UNKNOWN");

        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments).isNotEmpty();
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
