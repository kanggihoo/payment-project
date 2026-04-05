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
@Table(name = "orders") 
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 내부 식별자 (PK)

    @Column(nullable = false, unique = true)
    private String orderNumber; // 외부로 노출되는 주문 번호 (예: ORD-12345)

    @Column(nullable = false)
    private Long amount; // 주문 총 금액

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status; // 주문 상태 (CREATED, COMPLETED 등)

    @Column(name = "created_at")
    private OffsetDateTime createdAt; // 주문 생성 일시
}
