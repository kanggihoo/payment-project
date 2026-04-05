package com.example.payment;

import com.example.payment.service.PaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.wiremock.spring.EnableWireMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Red Test] 커넥션 풀 고갈 시나리오
 *
 * <p>시나리오: PG 응답이 10초 지연될 때, 커넥션 풀(5개)보다 많은 동시 요청(20개)이
 * 들어오면 일부 요청이 커넥션 타임아웃으로 실패함을 증명한다.
 *
 * <p>이 테스트가 PASS = 나이브 구현의 문제가 재현됨 (failCount > 0)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Timeout(120)
@EnableWireMock
@DisplayName("커넥션 풀 고갈 테스트")
class ConnectionPoolExhaustionTest {

    private static final int POOL_SIZE = 5;
    private static final int CONCURRENT_REQUESTS = 20;
    private static final int PG_DELAY_MS = 10_000; // 10초 지연

    @DynamicPropertySource
    static void overrideHikariAndPgUrl(DynamicPropertyRegistry registry) {
        // ⚠️ 풀 크기를 5로 제한 — 동시 요청 20개로 고갈 재현
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 3_000); // 3초 대기 후 실패
        // PG 클라이언트가 WireMock을 바라보도록
        registry.add("pg.base-url", ()-> "${wiremock.server.baseUrl}");
    }

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("PG 10초 지연 시 커넥션 풀(5) 초과 20개 동시 요청 → 일부 요청이 커넥션 타임아웃으로 실패한다")
    void whenPgIsSlowAndPoolIsExhausted_someRequestsFail() throws InterruptedException {
        // ---- WireMock Stub: POST /v1/payments/confirm → 10초 지연 후 200 ----
        stubFor(
                post(urlPathEqualTo("/v1/payments/confirm"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withFixedDelay(PG_DELAY_MS) // 10초 지연응답하도록 고정 
                                .withBody("""
                                        {
                                          "paymentKey": "test-key-001",
                                          "orderId": "ORD-001",
                                          "status": "DONE",
                                          "totalAmount": 10000
                                        }
                                        """))
        );

        // 1. 결과 카운트를 위한 안전한 계산기 (멀티스레드 환경에서도 숫자가 꼬이지 않음)
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        // 2. 동시에 요청을 보낼 스레드 준비(20개)
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<?>> futures = new ArrayList<>();

        // 3. 20개의 스레드에게 각각 결제 요청 업무를 할당 (비동기 실행)
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures.add(executor.submit(() -> {
                try {
                    // 실제 결제 로직 수행 (여기서 DB 커넥션을 잡고 10초 대기하게 됨)
                    paymentService.processPayment("ORD-001");
                    successCount.incrementAndGet(); // 성공 시 카운트 증가
                } catch (Exception e) {
                    // 커넥션 풀(5개)이 꽉 차서 실패할 경우 예외 발생 → 실패 카운트 증가
                    failCount.incrementAndGet();
                }
            }));
        }

        // 4. 모든 스레드 업무를 마칠 때까지 메인 스레드에서 대기
        for (Future<?> future : futures) {
            try {
                future.get(); // 각 스레드의 작업 완료를 기다림
            } catch (Exception ignored) {
                // 개별 작업의 예외는 위에서 이미 처리함
            }
        }
        executor.shutdown(); // 쓰레드 동작 종료 

        // 6. PG사(WireMock)로 실제로 요청이 몇 번이나 갔는지 검증
        // 이론상 커넥션 풀이 5개이므로, 20개를 요청했어도 실제로 PG 승인 단계까지 간 것은 5건이어야 함
        int receivedRequests = findAll(postRequestedFor(urlPathEqualTo("/v1/payments/confirm"))).size();
        System.out.println("[WireMock] 실제 수신된 요청 수: " + receivedRequests);
        
        // 실제로 5건의 요청이 도착했는지 확인 (풀 사이즈와 동일)
        verify(exactly(POOL_SIZE), postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));

        // 5. 핵심 검증: 실패한 요청이 최소 1건 이상 발생해야 함
        // (커넥션 풀이 5개인데 20명이 10초씩 잡고 있으면, 반드시 뒤에 온 사람들은 실패해야 정상)
        assertThat(failCount.get())
                .as("커넥션 풀(%d개) 초과, %d개 동시 요청 → 일부 요청은 반드시 타임아웃으로 실패해야 함 (나이브 구현 문제 증명)",
                        POOL_SIZE, CONCURRENT_REQUESTS)
                .isGreaterThan(0);

        // 테스트 결과 출력
        System.out.printf(
                "[ConnectionPoolExhaustion] 총 요청=%d | 성공=%d | 실패=%d%n",
                CONCURRENT_REQUESTS, successCount.get(), failCount.get()
        );
    }
}
