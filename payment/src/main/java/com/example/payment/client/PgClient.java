package com.example.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PgClient {

    private final RestClient restClient;
    private final String baseUrl;

    public PgClient(RestClient restClient, @Value("${pg.base-url}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    /**
     * PG사 결제 조회 API 호출 (GET /v1/payments/{paymentKey}).
     *
     * Phase 2에서 해결하는 문제:
     * UNKNOWN 상태 복구 스케줄러가 타임아웃된 결제의 최종 승인 여부를 확인할 때 사용한다.
     * 404 응답 시 PgPaymentException을 던져 FAILED로 처리할 수 있도록 한다.
     */
    public PgQueryResponse queryPayment(String pgPaymentKey) {
        try {
            return restClient.get()
                    .uri(baseUrl + "/v1/payments/" + pgPaymentKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                        throw new PgPaymentException("PG 조회 API 에러: " + res.getStatusCode());
                    })
                    .body(PgQueryResponse.class);
        } catch (PgPaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PgPaymentException("PG 조회 요청 실패: " + e.getMessage(), e);
        }
    }

    public PgApproveResponse approve(String orderNumber, Long amount) {
        PgApproveRequest request = new PgApproveRequest(orderNumber, amount);
        try {
            return restClient.post()
                    .uri(baseUrl + "/v1/payments/confirm")
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                        throw new PgPaymentException("PG 승인 API 에러: " + res.getStatusCode());
                    })
                    .body(PgApproveResponse.class);
        } catch (Exception e) {
            throw new PgPaymentException("PG 승인 요청 실패: " + e.getMessage(), e);
        }
    }
}
