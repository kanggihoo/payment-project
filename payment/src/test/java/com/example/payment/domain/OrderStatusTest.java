package com.example.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cycle 1: OrderStatus enum에 PENDING 값이 추가되었는지 검증한다.
 *
 * Phase 2에서 해결하는 문제:
 * TX1(사전 커밋)에서 Order를 PENDING 상태로 저장한 뒤 DB 커넥션을 반납하므로,
 * PENDING 상태가 없으면 트랜잭션 분리 구조 자체가 불가능하다.
 *
 * Red 유발: OrderStatus.PENDING이 존재하지 않아 컴파일 에러 발생 예정.
 */
class OrderStatusTest {

    @Test
    @DisplayName("OrderStatus는 PENDING 값을 포함해야 한다")
    void shouldContainPendingStatus() {
        // PENDING이 없으면 cannot find symbol 컴파일 에러 발생
        OrderStatus pending = OrderStatus.PENDING;
        assertThat(pending).isNotNull();
    }

    @Test
    @DisplayName("OrderStatus는 READY, PENDING, SUCCESS, FAILED 4개 값을 가져야 한다")
    void shouldHaveAllFourValues() {
        OrderStatus[] values = OrderStatus.values();
        assertThat(values).containsExactlyInAnyOrder(
                OrderStatus.READY,
                OrderStatus.PENDING,
                OrderStatus.SUCCESS,
                OrderStatus.FAILED
        );
    }
}
