package com.example.payment.scheduler;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.Order;
import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.PaymentInternalService;
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
 * Cycle 9: UnknownPaymentRecoveryScheduler 복구 로직 검증.
 *
 * Phase 2에서 해결하는 문제:
 * PG 타임아웃으로 UNKNOWN 상태가 된 결제를 1분 주기로 PG 조회 API를 통해 최종 상태로 확정한다.
 * - PG DONE → SUCCESS
 * - PG 404  → FAILED
 * - 네트워크 단절 → UNKNOWN 유지 (다음 주기에 재시도)
 *
 * 테스트 격리 전략:
 * 3개 테스트가 동일한 시드 데이터를 공유하면 첫 번째 테스트가 상태를 변경해 이후 테스트가 실패한다.
 * → @BeforeEach에서 각 테스트 전용 Order(PENDING) + Payment(UNKNOWN)를 직접 생성.
 * → @AfterEach에서 생성한 데이터를 삭제하여 다음 테스트에 영향 없도록 정리.
 * → @Transactional 미사용: recordSuccess/recordFailure가 별도 트랜잭션이므로 롤백하면 검증 불가.
 *
 * 스케줄러 자동 실행 방지:
 * @EnableScheduling은 SchedulingConfig에 분리되어 있으므로
 * 이 테스트에서 recover()를 직접 호출해도 자동 실행 간섭 없음.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableWireMock
@DisplayName("UnknownPaymentRecoveryScheduler - UNKNOWN 복구 테스트")
class UnknownPaymentRecoverySchedulerTest {

    @Autowired
    private UnknownPaymentRecoveryScheduler scheduler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentInternalService paymentInternalService;

    // 각 테스트에서 생성한 Order/Payment id를 보관 → @AfterEach 정리에 사용
    private Long testOrderId;
    private Long testPaymentId;

    static final String TEST_PG_KEY = "PG-KEY-TEST-RECOVERY";

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", () -> "${wiremock.server.baseUrl}");
    }

    /**
     * 각 테스트 전: 독립된 Order(PENDING) + Payment(UNKNOWN) 생성.
     * preparePayment() → recordUnknown() 순서로 실제 TX1/TX2를 거쳐 만든다.
     */
    @BeforeEach
    void setUp() {
        // 테스트 전용 READY 주문 생성
        Order order = orderRepository.save(new Order("ORD-RECOVERY-TEST", 4000L));
        testOrderId = order.getId();

        // TX1: PENDING으로 전환 + Payment(PENDING) 생성
        testPaymentId = paymentInternalService.preparePayment(testOrderId, 4000L);

        // Payment의 pgPaymentKey를 테스트 키로 설정하기 위해 직접 업데이트
        var payment = paymentRepository.findById(testPaymentId).orElseThrow();
        // TX2 타임아웃: Payment=UNKNOWN, Order=PENDING 유지
        paymentInternalService.recordUnknown(testPaymentId);

        // pgPaymentKey가 없으므로 JPQL 네이티브 업데이트로 직접 설정
        paymentRepository.setPaymentKeyForTest(testPaymentId, TEST_PG_KEY);
    }

    @AfterEach
    void tearDown() {
        // 테스트에서 생성한 데이터 정리 (시드 데이터에 영향 없도록)
        paymentRepository.deleteById(testPaymentId);
        orderRepository.deleteById(testOrderId);
    }

    @Test
    @DisplayName("PG 조회 DONE → Payment=SUCCESS, Order=SUCCESS")
    void whenPgDone_thenSuccess() {
        // given
        stubFor(get(urlPathEqualTo("/v1/payments/" + TEST_PG_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "%s",
                                  "orderId": "ORD-RECOVERY-TEST",
                                  "status": "DONE",
                                  "totalAmount": 4000
                                }
                                """.formatted(TEST_PG_KEY))));

        // when
        scheduler.recover();

        // then
        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("PG 조회 404 → Payment=FAILED, Order=FAILED")
    void whenPgNotFound_thenFailed() {
        // given
        stubFor(get(urlPathEqualTo("/v1/payments/" + TEST_PG_KEY))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"결제를 찾을 수 없습니다\"}")));

        // when
        scheduler.recover();

        // then
        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("PG 조회 네트워크 단절 → Payment=UNKNOWN 유지 (다음 주기에 재시도)")
    void whenNetworkError_thenUnknownRetained() {
        // given
        stubFor(get(urlPathEqualTo("/v1/payments/" + TEST_PG_KEY))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        // when — 네트워크 에러여도 예외가 전파되지 않고 UNKNOWN 유지
        scheduler.recover();

        // then
        var order = orderRepository.findById(testOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(testOrderId);
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
