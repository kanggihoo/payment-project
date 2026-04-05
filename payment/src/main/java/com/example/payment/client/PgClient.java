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
