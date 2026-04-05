package com.example.payment.controller;

import com.example.payment.service.PaymentResult;
import com.example.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResult> pay(@RequestBody PaymentRequest request) {
        try {
            PaymentResult result = paymentService.processPayment(request.orderNumber());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 에러 발생 시에도 동일한 PaymentResult 객체를 반환하여 타입을 맞춤
            return ResponseEntity.internalServerError()
                    .body(new PaymentResult(request.orderNumber(), "FAILED", null));
        }
    }
}
