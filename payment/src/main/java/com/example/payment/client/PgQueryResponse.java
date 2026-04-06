package com.example.payment.client;

/**
 * PG사 결제 조회 API(GET /v1/payments/{paymentKey}) 응답 DTO.
 *
 * Phase 2에서 해결하는 문제:
 * UNKNOWN 상태 복구 스케줄러가 PG에서 최종 결제 상태를 확인할 때 사용한다.
 * status가 "DONE"이면 SUCCESS로 확정, 그 외는 FAILED로 처리한다.
 */
public record PgQueryResponse(
        String paymentKey,  // PG사에서 발행한 결제 고유 키
        String orderId,     // 주문 ID
        String status,      // 결제 상태 (예: DONE)
        Long totalAmount    // 결제 금액
) {
}
