package com.example.payment.repository;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    long countByOrderId(Long orderId);

    // Cycle 7 선행 준비: UNKNOWN 상태 결제 목록 조회 (복구 스케줄러에서 사용)
    List<Payment> findByStatus(PaymentStatus status);

    // Cycle 5 선행 준비: orderId로 결제 목록 최신순 조회 (getPaymentStatus()에서 사용)
    List<Payment> findByOrderIdOrderByIdDesc(Long orderId);

    // 테스트 전용: recordUnknown() 후 pgPaymentKey가 null인 Payment에 키를 직접 설정
    // Payment 도메인 생성자가 pgPaymentKey를 받지 않으므로 테스트 격리를 위해 필요
    @Modifying
    @Transactional
    @Query("UPDATE Payment p SET p.pgPaymentKey = :pgPaymentKey WHERE p.id = :paymentId")
    void setPaymentKeyForTest(Long paymentId, String pgPaymentKey);
}
