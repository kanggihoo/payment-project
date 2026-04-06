package com.example.payment.service;

import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.PaymentStatus;

/**
 * 결제 상태 조회 응답 DTO.
 *
 * Phase 2에서 해결하는 문제:
 * UNKNOWN 상태에서 클라이언트가 폴링으로 최종 상태를 확인할 때 사용한다.
 * orderStatus + paymentStatus를 함께 반환하여 클라이언트가 현재 처리 단계를 파악할 수 있도록 한다.
 */
public record PaymentStatusResponse(
        String orderNumber,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        String pgPaymentKey  // 결제 완료 시에만 존재, PENDING/UNKNOWN이면 null
) {
}
