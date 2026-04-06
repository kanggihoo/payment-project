package com.example.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "pg_payment_key")
    private String pgPaymentKey;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    // TX1 사전 커밋: Order.id와 금액을 받아 PENDING 상태로 생성
    public Payment(Long orderId, Long amount) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    // TX2 완료: pgPaymentKey를 함께 저장
    public void success(String pgPaymentKey) {
        this.pgPaymentKey = pgPaymentKey;
        this.status = PaymentStatus.SUCCESS;
    }

    // TX2 실패: PG 승인 거절
    public void fail() {
        this.status = PaymentStatus.FAILED;
    }

    // TX2 타임아웃: 승인 여부 불명 → 복구 스케줄러가 처리
    public void unknown() {
        this.status = PaymentStatus.UNKNOWN;
    }
}
