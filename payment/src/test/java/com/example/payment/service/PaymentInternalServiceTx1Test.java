package com.example.payment.service;

import com.example.payment.TestcontainersConfiguration;
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
import com.example.payment.domain.Order;
import com.example.payment.domain.Payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle 3: PaymentInternalService.preparePayment() TX1 검증.
 *
 * Phase 2에서 해결하는 문제:
 * TX1은 Order를 PENDING으로 전환하고 Payment(PENDING)를 INSERT한 뒤 즉시 커밋한다.
 * 커밋 후 DB 커넥션이 반납되므로, PG 호출 시간 동안 커넥션을 점유하지 않는다.
 * → 커넥션 풀 고갈 문제 해결의 핵심.
 *
 * 테스트 격리: @Transactional로 각 테스트 후 자동 롤백하여 시드 데이터 상태를 유지한다.
 *
 * Red 유발: PaymentInternalService 클래스, preparePayment() 메서드가 없어 컴파일 에러 발생 예정.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("PaymentInternalService - TX1 사전 커밋 테스트")
class PaymentInternalServiceTx1Test {

    @Autowired
    private PaymentInternalService paymentInternalService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("정상 호출 시 Order=PENDING, Payment=PENDING으로 저장되고 paymentId를 반환한다")
    void whenValidOrder_thenPreparePayment() {
        // given - 시드 데이터 ORD-001 (READY 상태)
        Long orderId = orderRepository.findByOrderNumber("ORD-001")
                .orElseThrow().getId();

        // when
        Long paymentId = paymentInternalService.preparePayment(orderId, 1000L); // Order를 READY -> PENDING으로 전환하고 ,
                                                                                // Payment객체 생성 한뒤 커밋 후 결제 ID 반환

        // then
        assertThat(paymentId).isNotNull();

        // Order가 PENDING으로 전환되었는지 확인
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        // Payment가 PENDING 상태로 생성되었는지 확인
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getOrderId()).isEqualTo(orderId);
        assertThat(payment.getAmount()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("존재하지 않는 orderId 전달 시 IllegalArgumentException을 던진다")
    void whenOrderNotFound_thenThrowIllegalArgumentException() {
        assertThatThrownBy(() -> paymentInternalService.preparePayment(999999L, 1000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("READY가 아닌 Order(PENDING)에 preparePayment 호출 시 IllegalStateException을 던진다")
    void whenOrderNotReady_thenThrowIllegalStateException() {
        // given - 시드 데이터 ORD-PENDING (PENDING 상태)
        Long orderId = orderRepository.findByOrderNumber("ORD-PENDING")
                .orElseThrow().getId();

        assertThatThrownBy(() -> paymentInternalService.preparePayment(orderId, 2000L)) //
                .isInstanceOf(IllegalStateException.class);
    }
}
