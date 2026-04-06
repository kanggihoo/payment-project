package com.example.payment.service;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.Order;
import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import com.example.payment.domain.Payment;
import com.example.payment.domain.Order;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cycle 4: PaymentInternalService TX2 — PG 결과에 따른 상태 기록 메서드 3개 검증.
 *
 * Phase 2에서 해결하는 문제:
 * PG 호출 결과(트랜잭션 밖)를 받아 TX2에서 DB에 최종 상태를 기록한다.
 * - recordSuccess: PG 승인 성공 → Payment=SUCCESS, Order=SUCCESS
 * - recordFailure: PG 승인 거절 → Payment=FAILED, Order=FAILED
 * - recordUnknown: PG 타임아웃 → Payment=UNKNOWN, Order=PENDING 유지
 * (타임아웃 시 PG에서 실제로 승인됐을 수 있으므로 FAILED 확정은 위험)
 *
 * 테스트 격리: 각 테스트에서 TX1을 직접 호출해 PENDING Payment를 생성하고,
 * 
 * @Transactional로 롤백하여 시드 데이터 상태를 유지한다.
 *
 *                 Red 유발: recordSuccess(), recordFailure(), recordUnknown()
 *                 메서드가 없어 컴파일 에러 발생 예정.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("PaymentInternalService - TX2 상태 기록 테스트")
class PaymentInternalServiceTx2Test {

    @Autowired
    private PaymentInternalService paymentInternalService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("recordSuccess: Payment=SUCCESS, Order=SUCCESS, pgPaymentKey 저장")
    void recordSuccess_shouldSetBothToSuccess() {
        // given - TX1으로 PENDING 상태 준비
        Long orderId = orderRepository.findByOrderNumber("ORD-001").orElseThrow().getId();
        Long paymentId = paymentInternalService.preparePayment(orderId, 1000L);

        // when
        paymentInternalService.recordSuccess(paymentId, "pg-key-success-001");

        // then
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPgPaymentKey()).isEqualTo("pg-key-success-001");

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);
    }

    @Test
    @DisplayName("recordFailure: Payment=FAILED, Order=FAILED")
    void recordFailure_shouldSetBothToFailed() {
        // given
        Long orderId = orderRepository.findByOrderNumber("ORD-002").orElseThrow().getId();
        Long paymentId = paymentInternalService.preparePayment(orderId, 5000L);

        // when
        // Red 유발: recordFailure() 없어 컴파일 에러
        paymentInternalService.recordFailure(paymentId);

        // then
        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("recordUnknown: Payment=UNKNOWN, Order는 PENDING 유지")
    void recordUnknown_shouldSetPaymentUnknownAndKeepOrderPending() {
        // given
        Long orderId = orderRepository.findByOrderNumber("ORD-001").orElseThrow().getId();
        Long paymentId = paymentInternalService.preparePayment(orderId, 1000L);

        // when
        // Red 유발: recordUnknown() 없어 컴파일 에러
        paymentInternalService.recordUnknown(paymentId);

        // then
        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        // Order는 PENDING 유지 — 타임아웃이지 실패 확정이 아님
        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}
