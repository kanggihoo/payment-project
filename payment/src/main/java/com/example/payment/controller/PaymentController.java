package com.example.payment.controller;

import com.example.payment.service.PaymentResult;
import com.example.payment.service.PaymentService;
import com.example.payment.service.PaymentStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 API 컨트롤러.
 *
 * Phase 2에서 추가된 동작:
 * 1. processPayment() 결과가 UNKNOWN이면 HTTP 202 Accepted 반환
 *    — 클라이언트에게 "처리 중, 폴링으로 최종 상태 확인 필요"를 알림
 * 2. GET /{orderNumber}/status 엔드포인트 추가
 *    — UNKNOWN 상태에서 클라이언트 폴링용
 *    — 존재하지 않는 주문 → IllegalArgumentException → 404
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResult> pay(@RequestBody PaymentRequest request) {
        PaymentResult result = paymentService.processPayment(request.orderNumber());

        // UNKNOWN: PG 타임아웃으로 승인 여부 불명 → 202 Accepted
        // 클라이언트는 GET /status 폴링으로 복구 스케줄러의 최종 상태 확정을 기다려야 한다
        if ("UNKNOWN".equals(result.status())) {
            return ResponseEntity.accepted().body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 결제 상태 조회.
     *
     * UNKNOWN 상태에서 클라이언트 폴링용. 복구 스케줄러가 상태를 확정하면
     * 이 API를 통해 SUCCESS 또는 FAILED를 확인할 수 있다.
     *
     * @param orderNumber 주문 번호
     * @return 주문 + 결제 상태 (orderStatus, paymentStatus, pgPaymentKey)
     * @throws IllegalArgumentException 주문이 존재하지 않을 때 → 404로 매핑
     */
    @GetMapping("/{orderNumber}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable String orderNumber) {
        try {
            PaymentStatusResponse response = paymentService.getPaymentStatus(orderNumber);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // 존재하지 않는 주문 → 404
            return ResponseEntity.notFound().build();
        }
    }
}
