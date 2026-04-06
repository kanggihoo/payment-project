package com.example.payment.service;

import com.example.payment.client.PgApproveResponse;
import com.example.payment.client.PgClient;
import com.example.payment.client.PgPaymentException;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 결제 오케스트레이터.
 *
 * Phase 2 TX 분리 구조:
 * TX1(사전 커밋) → PG 호출(트랜잭션 밖) → TX2(결과 기록)
 *
 * Phase 1 문제 해결:
 * 1. 커넥션 풀 고갈 — TX1 커밋 후 커넥션 반납 → PG 호출 시간 동안 커넥션 미점유
 * 2. 데이터 불일치 — TX1에서 Payment(PENDING)를 먼저 저장하므로 PG 성공 후 DB 실패해도 PENDING 기록이 남음
 *
 * 클래스 레벨 @Transactional 제거:
 * TX1/TX2는 PaymentInternalService(별도 빈)에서 각각 독립 트랜잭션으로 실행된다.
 * 이 클래스에 @Transactional이 있으면 외부 트랜잭션에 TX1/TX2가 합류되어 분리 효과가 사라진다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentInternalService paymentInternalService;

    /**
     * 결제 처리 오케스트레이션.
     *
     * TX1: Order=PENDING, Payment=PENDING 사전 커밋 → DB 커넥션 반납
     * PG:  트랜잭션 밖에서 PG 승인 요청
     * TX2: PG 결과에 따라 SUCCESS / FAILED / UNKNOWN 기록
     */
    public PaymentResult processPayment(String orderNumber) {
        var order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderNumber));

        // TX1: 사전 커밋 — 이 시점부터 DB 커넥션 반납됨
        Long paymentId = paymentInternalService.preparePayment(order.getId(), order.getAmount());

        // PG 호출 — 트랜잭션 밖, DB 커넥션 미점유
        try {
            PgApproveResponse pgResponse = pgClient.approve(orderNumber, order.getAmount());

            // TX2: 성공 기록
            paymentInternalService.recordSuccess(paymentId, pgResponse.paymentKey());
            return new PaymentResult(orderNumber, "SUCCESS", pgResponse.paymentKey());

        } catch (PgPaymentException e) {
            if (isNetworkError(e)) {
                // TX2: 타임아웃 — 승인 여부 불명, 복구 스케줄러가 처리
                paymentInternalService.recordUnknown(paymentId);
                return new PaymentResult(orderNumber, "UNKNOWN", null);
            }
            // TX2: PG 명시적 거절(4xx)
            paymentInternalService.recordFailure(paymentId);
            return new PaymentResult(orderNumber, "FAILED", null);
        }
    }

    /**
     * 결제 상태 조회.
     *
     * UNKNOWN 상태에서 클라이언트가 폴링으로 최종 상태를 확인할 때 사용한다.
     * findByOrderIdOrderByIdDesc로 DB 레벨 직접 조회 — findAll() 후 스트림 필터 방식 금지.
     */
    public PaymentStatusResponse getPaymentStatus(String orderNumber) {
        var order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderNumber));

        var payments = paymentRepository.findByOrderIdOrderByIdDesc(order.getId());
        var latestPayment = payments.isEmpty() ? null : payments.get(0);

        return new PaymentStatusResponse(
                orderNumber,
                order.getStatus(),
                latestPayment != null ? latestPayment.getStatus() : null,
                latestPayment != null ? latestPayment.getPgPaymentKey() : null
        );
    }

    // PgClient에서 HTTP 오류(4xx/5xx)는 "PG 승인 API 에러:", 네트워크 오류는 "PG 승인 요청 실패:"로 구분된다.
    private boolean isNetworkError(PgPaymentException e) {
        return e.getMessage() != null && e.getMessage().startsWith("PG 승인 요청 실패");
    }
}
