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
@Table(name = "orders")
@Getter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public Order(String orderNumber, Long amount) {
        this.orderNumber = orderNumber;
        this.amount = amount;
        this.status = OrderStatus.READY;
        this.createdAt = OffsetDateTime.now();
    }

    // TX1 사전 커밋: READY → PENDING 전환 후 DB 커넥션 반납
    public void pending() {
        if (this.status != OrderStatus.READY) {
            throw new IllegalStateException("READY 상태에서만 PENDING으로 전환할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = OrderStatus.PENDING;
    }

    // TX2 완료: PENDING → SUCCESS 전환
    public void success() {
        this.status = OrderStatus.SUCCESS;
    }

    // TX2 실패: PENDING → FAILED 전환
    public void fail() {
        this.status = OrderStatus.FAILED;
    }
}
