package com.example.payment.client;

import com.example.payment.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.wiremock.spring.EnableWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle 2: PgClient.queryPayment() 메서드와 PgQueryResponse 검증.
 *
 * Phase 2에서 해결하는 문제:
 * UNKNOWN 상태 복구 스케줄러가 PG 조회 API를 호출하여 최종 결제 상태를 확정해야 한다.
 * approve()는 결제 요청이고, queryPayment()는 기존 결제 결과 조회다.
 *
 * Red 유발: PgQueryResponse 클래스, pgClient.queryPayment() 메서드가 존재하지 않아 컴파일 에러 발생
 * 예정.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableWireMock
@DisplayName("PgClient - 결제 조회 API 테스트")
class PgClientQueryTest {

    @Autowired
    private PgClient pgClient;

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", () -> "${wiremock.server.baseUrl}");
    }

    @Test
    @DisplayName("PG 조회 성공 시 PgQueryResponse를 반환한다")
    void whenQuerySuccess_thenReturnPgQueryResponse() {
        // given
        String pgPaymentKey = "pg-key-success-001";
        stubFor(get(urlPathEqualTo("/v1/payments/" + pgPaymentKey))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "pg-key-success-001",
                                  "orderId": "ORD-001",
                                  "status": "DONE",
                                  "totalAmount": 10000
                                }
                                """)));

        // when
        PgQueryResponse response = pgClient.queryPayment(pgPaymentKey);

        // then
        assertThat(response.paymentKey()).isEqualTo("pg-key-success-001");
        assertThat(response.orderId()).isEqualTo("ORD-001");
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.totalAmount()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("PG 조회 4xx 시 PgPaymentException을 던진다")
    void whenQuery4xx_thenThrowPgPaymentException() {
        // given - 존재하지 않는 paymentKey 조회
        String unknownKey = "pg-key-not-found";
        stubFor(get(urlPathEqualTo("/v1/payments/" + unknownKey))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"결제 내역을 찾을 수 없습니다\"}")));

        // when & then
        assertThatThrownBy(() -> pgClient.queryPayment(unknownKey))
                .isInstanceOf(PgPaymentException.class);
    }
}
