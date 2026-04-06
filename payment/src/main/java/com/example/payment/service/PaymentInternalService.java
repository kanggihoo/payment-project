package com.example.payment.service;

import com.example.payment.domain.Order;
import com.example.payment.domain.Payment;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TX1, TX2를 별도 빈으로 분리한 내부 서비스.
 *
 * Phase 2에서 해결하는 문제:
 * PaymentService 안에서 this.method()로 TX1/TX2를 호출하면 Spring AOP 프록시가 우회되어
 * @Transactional이 무시된다(self-invocation 함정).
 * 별도 빈으로 분리함으로써 프록시를 거쳐 독립된 트랜잭션이 보장된다.
 */
@Service
@RequiredArgsConstructor
public class PaymentInternalService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    /**
     * TX1 사전 커밋: Order를 PENDING으로 전환하고 Payment(PENDING)를 INSERT한 뒤 즉시 커밋.
     *
     * 커밋 후 DB 커넥션이 반납되므로, 이후 PG 호출 시간 동안 커넥션을 점유하지 않는다.
     * → 커넥션 풀 고갈 문제 해결의 핵심.
     *
     * @return 생성된 Payment의 id (TX2에서 상태 업데이트 시 사용)
     */
    @Transactional
    public Long preparePayment(Long orderId, Long amount) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));

        // READY 상태 검증은 Order.pending() 내부에서 수행 (IllegalStateException 발생)
        order.pending();

        Payment payment = new Payment(orderId, amount);
        paymentRepository.save(payment);

        return payment.getId();
    }

    /**
     * TX2 성공 경로: Payment=SUCCESS, Order=SUCCESS, pgPaymentKey 저장.
     *
     * PG 승인이 확정된 경우에만 호출된다.
     * pgPaymentKey는 환불/취소 시 PG사 식별에 반드시 필요하므로 이 시점에 저장한다.
     */
    @Transactional
    public void recordSuccess(Long paymentId, String pgPaymentKey) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();

        payment.success(pgPaymentKey);
        order.success();
    }

    /**
     * TX2 실패 경로: Payment=FAILED, Order=FAILED.
     *
     * PG 승인이 명시적으로 거절(4xx)된 경우 호출된다.
     */
    @Transactional
    public void recordFailure(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();

        payment.fail();
        order.fail();
    }

    /**
     * TX2 타임아웃 경로: Payment=UNKNOWN, Order는 PENDING 유지.
     *
     * 타임아웃 시 PG에서 실제로 승인됐을 수 있으므로 FAILED로 확정하는 것은 위험하다.
     * 복구 스케줄러(UnknownPaymentRecoveryScheduler)가 1분 주기로 PG 조회 API를 호출해 최종 상태를 확정한다.
     */
    @Transactional
    public void recordUnknown(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();

        payment.unknown();
        // Order는 변경하지 않는다 — PENDING 유지
    }
}
