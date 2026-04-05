package com.example.payment.service;

public record PaymentResult(String orderNumber, String status, String paymentKey) {
}
