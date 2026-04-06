package com.example.payment.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cycle 3: Payment 도메인 객체의 상태 전환 메서드를 검증한다.
 *
 * Phase 2에서 해결하는 문제:
 * TX1 사전 커밋 시 Payment를 PENDING 상태로 미리 저장한다.
 * PG 호출 결과에 따라 SUCCESS / FAILED / UNKNOWN 으로 전환한다.
 * - UNKNOWN: PG 타임아웃 시 → 복구 스케줄러가 나중에 처리
 */
class PaymentTest {

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = new Payment(1L, 10_000L);
    }

    @Test
    @DisplayName("Payment는 생성 시 PENDING 상태여야 한다")
    void shouldBePendingOnCreation() {
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING 상태의 Payment는 pgPaymentKey와 함께 SUCCESS로 전환할 수 있다")
    void shouldTransitionFromPendingToSuccess() {
        payment.success("pg-key-abc123");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPgPaymentKey()).isEqualTo("pg-key-abc123");
    }

    @Test
    @DisplayName("PENDING 상태의 Payment는 FAILED로 전환할 수 있다")
    void shouldTransitionFromPendingToFailed() {
        payment.fail();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("PENDING 상태의 Payment는 PG 타임아웃 시 UNKNOWN으로 전환할 수 있다")
    void shouldTransitionFromPendingToUnknown() {
        payment.unknown();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
