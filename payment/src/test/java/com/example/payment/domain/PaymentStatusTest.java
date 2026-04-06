package com.example.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cycle 1: PaymentStatus enum에 PENDING, UNKNOWN 값이 추가되었는지 검증한다.
 *
 * Phase 2에서 해결하는 문제:
 * - PENDING: TX1 사전 커밋 시 결제 레코드 초기 상태
 * - UNKNOWN: PG 호출 타임아웃 시 승인 여부 불명 상태 → 복구 스케줄러가 처리
 *
 * Red 유발: PaymentStatus.PENDING, PaymentStatus.UNKNOWN이 존재하지 않아 컴파일 에러 발생 예정.
 */
class PaymentStatusTest {

    @Test
    @DisplayName("PaymentStatus는 PENDING 값을 포함해야 한다")
    void shouldContainPendingStatus() {
        // PENDING이 없으면 cannot find symbol 컴파일 에러 발생
        PaymentStatus pending = PaymentStatus.PENDING;
        assertThat(pending).isNotNull();
    }

    @Test
    @DisplayName("PaymentStatus는 UNKNOWN 값을 포함해야 한다")
    void shouldContainUnknownStatus() {
        // UNKNOWN이 없으면 cannot find symbol 컴파일 에러 발생
        PaymentStatus unknown = PaymentStatus.UNKNOWN;
        assertThat(unknown).isNotNull();
    }

    @Test
    @DisplayName("PaymentStatus는 READY, PENDING, SUCCESS, FAILED, UNKNOWN 5개 값을 가져야 한다")
    void shouldHaveAllFiveValues() {
        PaymentStatus[] values = PaymentStatus.values();
        assertThat(values).containsExactlyInAnyOrder(
                PaymentStatus.READY,
                PaymentStatus.PENDING,
                PaymentStatus.SUCCESS,
                PaymentStatus.FAILED,
                PaymentStatus.UNKNOWN
        );
    }
}
