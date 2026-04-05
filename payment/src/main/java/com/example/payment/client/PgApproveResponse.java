package com.example.payment.client;

public record PgApproveResponse(
        String paymentKey,  // PG사에서 발행한 결제 고유 키
        String orderId,     // 주문 ID
        String status,      // 결제 상태 (예: DONE)
        Long totalAmount    // 실제 결제된 총 금액
) {
}
