package com.example.payment.service;

import com.example.payment.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle 5: PaymentService.getPaymentStatus() 조회 검증.
 *
 * Phase 2에서 해결하는 문제:
 * UNKNOWN 상태에서 클라이언트가 폴링으로 최종 상태를 확인할 수 있어야 한다.
 * orderId로 직접 조회(findByOrderIdOrderByIdDesc)하여 전체 조회 후 스트림 필터 방식을 금지한다.
 *
 * Red 유발: PaymentStatusResponse 클래스, getPaymentStatus() 메서드가 없어 컴파일 에러 발생 예정.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("PaymentService - 결제 상태 조회 테스트")
class PaymentStatusQueryTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("ORD-UNKNOWN 조회 시 PaymentStatusResponse를 반환한다")
    void whenValidOrder_thenReturnPaymentStatusResponse() {
        // given - 시드 데이터: ORD-UNKNOWN(PENDING) + Payment(UNKNOWN)

        // when
        // Red 유발: PaymentStatusResponse, getPaymentStatus() 없어 컴파일 에러
        PaymentStatusResponse response = paymentService.getPaymentStatus("ORD-UNKNOWN");

        // then
        assertThat(response.orderNumber()).isEqualTo("ORD-UNKNOWN");
        assertThat(response.pgPaymentKey()).isEqualTo("PG-KEY-UNKNOWN-001");
    }

    @Test
    @DisplayName("존재하지 않는 orderNumber 조회 시 IllegalArgumentException을 던진다")
    void whenOrderNotFound_thenThrowIllegalArgumentException() {
        assertThatThrownBy(() -> paymentService.getPaymentStatus("NOT-EXIST"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
