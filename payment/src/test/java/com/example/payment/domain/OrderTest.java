package com.example.payment.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle 2: Order 도메인 객체의 상태 전환 메서드를 검증한다.
 *
 * Phase 2에서 해결하는 문제:
 * TX1(사전 커밋)에서 Order를 PENDING 상태로 전환해야 한다.
 * 도메인 객체가 자신의 상태 전환을 직접 책임지도록 한다.
 * (Setter 직접 사용 -> 명시적 메서드로 대체)
 */
class OrderTest {

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order("ORD-001", 10_000L);
    }

    @Test
    @DisplayName("Order는 생성 시 READY 상태여야 한다")
    void shouldBeReadyOnCreation() {
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY);
    }

    @Test
    @DisplayName("READY 상태의 Order는 PENDING으로 전환할 수 있다") // READY 상태에서만 PENDING으로 전환 가능
    void shouldTransitionFromReadyToPending() {
        order.pending();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING 상태의 Order는 SUCCESS로 전환할 수 있다")
    void shouldTransitionFromPendingToSuccess() {
        order.pending();
        order.success();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);
    }

    @Test
    @DisplayName("PENDING 상태의 Order는 FAILED로 전환할 수 있다")
    void shouldTransitionFromPendingToFailed() {
        order.pending();
        order.fail();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("READY 상태가 아닌 Order는 PENDING으로 전환할 수 없다") // PENDING 상태에서 다시 PENDING으로 전환 시 예외 발생
    void shouldNotTransitionToPendingFromNonReadyState() {
        order.pending(); // READY -> PENDING
        assertThatThrownBy(() -> order.pending())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
    }
}
