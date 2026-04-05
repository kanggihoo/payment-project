package com.example.payment;

import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.wiremock.spring.EnableWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableWireMock
@DisplayName("데이터 정합성 오류 테스트 — Spy 시나리오")
class DataInconsistencyWithSpyTest {

    @Autowired
    private PaymentService paymentService;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        // @EnableWireMock이 제공하는 baseUrl을 동적으로 주입
        registry.add("pg.base-url", () -> "${wiremock.server.baseUrl}");
    }

    @Test
    @DisplayName("PG 승인은 성공했으나 DB 저장 실패 시 → PG 취소 안 됨 & DB 롤백 (정합성 부재 증명)")
    void whenSaveFails_afterPgSuccess_thenInconsistent() {
        // [1] 외부 PG 승인은 성공함! (가짜 서버 셋업)
        stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "test-key-007",
                                  "orderId": "ORD-001",
                                  "status": "DONE",
                                  "totalAmount": 10000
                                }
                                """)));

        // [2] DB 저장 시 에러 강제 주입 (DB 저장 실패 시뮬레이션)
        doThrow(new RuntimeException("DB 저장 실패!")).when(paymentRepository).save(any());

        // [3] 로직 실행: PG 승인 후 DB 저장 단계에서 예외 발생
        assertThatThrownBy(() -> paymentService.processPayment("ORD-001"))
                .isInstanceOf(RuntimeException.class);

        // [4] 결과 검증 1: PG사는 실제로 호출되었음 (사용자 돈은 이미 빠져나감)
        verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));

        // [5] 결과 검증 2: 하지만 우리 DB에는 결제 기록이 없음 (롤백되었기 때문)
        // 결론: PG 성공(돈나감) vs DB 부재(기록없음) -> 데이터 부정합 발생 확인!
        assertThat(paymentRepository.findAll()).isEmpty();
        
        System.out.println(">>> [SpyTest] 정합성 오류 재현 성공!! PG 성공 vs DB 부재");
    }
}
