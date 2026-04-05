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
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 내부 식별자 (PK)

    // ⚠️ 나이브 구현: orderId를 plain Long으로 (FK 관계 없음)
    @Column(name = "order_id", nullable = false)
    private Long orderId; // 연관된 주문 번호 (Order.id)

    @Column(name = "pg_payment_key")
    private String pgPaymentKey; // PG사에서 발행한 결제 고유 키

    @Column(nullable = false)
    private Long amount; // 실제 결제 요청/완료 금액

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status; // 결제 상태 (READY, DONE, FAILED 등)

    @Column(name = "created_at")
    private OffsetDateTime createdAt; // 결제 데이터 생성 일시(데이터가 삽입될 때, 값을 따로 주지 않으면 DB가 현재 시각을 자동으로 채음)
}
