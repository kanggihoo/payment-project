package com.example.payment.client;

public record PgApproveRequest(
        String orderId, // 주문 ID
        Long amount     // 결제 금액
) {
}
