package com.example.payment.repository;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    long countByOrderId(Long orderId);

    // Cycle 7 선행 준비: UNKNOWN 상태 결제 목록 조회 (복구 스케줄러에서 사용)
    List<Payment> findByStatus(PaymentStatus status);

    // Cycle 5 선행 준비: orderId로 결제 목록 최신순 조회 (getPaymentStatus()에서 사용)
    List<Payment> findByOrderIdOrderByIdDesc(Long orderId);
}
