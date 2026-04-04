# Phase 2. 트랜잭션 분리 + Unknown State 핸들링

> "응답을 받지 못했다면, 결제는 성공한 것인가 실패한 것인가?"

---

## 이 Phase의 목표

- Phase 1의 두 가지 문제(커넥션 고갈 + 데이터 불일치)를 **트랜잭션 분리**로 해결한다
- 분리 후 새롭게 드러나는 **Unknown State** 문제를 직접 경험한다
- 상태 머신(`PENDING → SUCCESS / FAILED`)과 **PG 조회 API를 통한 복구 메커니즘**을 구현한다

---

## 이전 Phase의 문제를 어떻게 해결하는가

```
Phase 1 (문제):
  @Transactional {
    주문 조회 → PG 승인 요청(10초) → 결제 내역 저장
  }
  → 커넥션을 10초간 점유 → 고갈

Phase 2 (해결):
  TX1 { 주문 상태를 PENDING으로 저장 }  → 커밋 (커넥션 반납)
  PG 승인 요청 (트랜잭션 밖 — 커넥션 없이 대기)
  TX2 { 결과에 따라 SUCCESS / FAILED 업데이트 }
```

---

## 2-1. DB 스키마 변경

```sql
-- orders 테이블에 상태 추가
-- 기존: READY, SUCCESS, FAILED
-- 변경: READY, PENDING, SUCCESS, FAILED

-- payment 테이블에 상태 추가
-- 기존: READY, SUCCESS, FAILED
-- 변경: READY, PENDING, SUCCESS, FAILED, UNKNOWN
```

```java
public enum OrderStatus {
    READY,     // 주문 생성됨
    PENDING,   // 결제 진행 중 (PG 요청 전 사전 커밋)
    SUCCESS,   // 결제 완료
    FAILED     // 결제 실패
}

public enum PaymentStatus {
    READY,     // 결제 대기
    PENDING,   // PG 요청 중
    SUCCESS,   // 결제 성공
    FAILED,    // 결제 실패
    UNKNOWN    // PG 응답을 받지 못함 (타임아웃 등)
}
```

---

## 2-2. 트랜잭션 분리 구현

### Service — TX 분리 구조

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentInternalService internalService;
    private final PgClient pgClient;
    private final MeterRegistry meterRegistry;

    /**
     * 트랜잭션을 3단계로 분리한다:
     * TX1: PENDING 사전 커밋 → 커넥션 반납
     * (외부 호출): PG 승인 요청 → 커넥션 없이 대기
     * TX2: 결과 반영 → SUCCESS or FAILED
     */
    public PaymentResult processPayment(String orderNumber) {
        // === TX1: 사전 커밋 ===
        PaymentPendingResult pending = internalService.createPendingPayment(orderNumber);
        // 이 시점에 커밋 완료 → DB 커넥션 반납됨

        try {
            // === 외부 호출: 트랜잭션 밖 ===
            PgApproveResponse pgResponse = pgClient.approve(
                pending.orderNumber(), pending.amount()
            );

            // === TX2: 성공 반영 ===
            internalService.completePayment(pending.paymentId(), pgResponse.paymentKey());
            return new PaymentResult(orderNumber, "SUCCESS", pgResponse.paymentKey());

        } catch (ResourceAccessException e) {
            // 타임아웃 — PG에 요청이 갔는지 안 갔는지 모름 (Unknown State)
            internalService.markUnknown(pending.paymentId());
            meterRegistry.counter("payment.state.unknown").increment();
            return new PaymentResult(orderNumber, "UNKNOWN", null);

        } catch (PgPaymentException e) {
            // PG가 명확히 거절
            internalService.failPayment(pending.paymentId());
            return new PaymentResult(orderNumber, "FAILED", null);

        } catch (Exception e) {
            // 기타 예외
            internalService.markUnknown(pending.paymentId());
            meterRegistry.counter("payment.state.unknown").increment();
            return new PaymentResult(orderNumber, "UNKNOWN", null);
        }
    }
}
```

### Internal Service — 각 TX를 독립 메서드로

```java
@Service
@RequiredArgsConstructor
public class PaymentInternalService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;

    /**
     * TX1: PENDING 상태로 사전 커밋
     * 이 시점에 DB 커넥션을 반납하므로 PG 응답을 기다리는 동안 커넥션을 점유하지 않는다.
     */
    @Transactional
    public PaymentPendingResult createPendingPayment(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new IllegalArgumentException("주문 없음: " + orderNumber));

        order.setStatus(OrderStatus.PENDING);
        order.setUpdatedAt(LocalDateTime.now());

        Payment payment = new Payment(order.getId(), order.getAmount());
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment);

        meterRegistry.counter("payment.state.pending").increment();

        return new PaymentPendingResult(payment.getId(), order.getOrderNumber(), order.getAmount());
    }

    /**
     * TX2: PG 승인 성공 시 — SUCCESS 반영
     */
    @Transactional
    public void completePayment(Long paymentId, String pgPaymentKey) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPgPaymentKey(pgPaymentKey);
        payment.setUpdatedAt(LocalDateTime.now());

        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        order.setStatus(OrderStatus.SUCCESS);
        order.setUpdatedAt(LocalDateTime.now());

        meterRegistry.counter("payment.state.success").increment();
    }

    /**
     * TX2: PG가 명확히 거절 시 — FAILED 반영
     */
    @Transactional
    public void failPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setStatus(PaymentStatus.FAILED);
        payment.setUpdatedAt(LocalDateTime.now());

        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        order.setStatus(OrderStatus.FAILED);
        order.setUpdatedAt(LocalDateTime.now());

        meterRegistry.counter("payment.state.failed").increment();
    }

    /**
     * TX2: 타임아웃/네트워크 오류 시 — UNKNOWN 마킹
     * 이 상태의 결제는 "PG 조회 API"로 실제 승인 여부를 확인해야 한다.
     */
    @Transactional
    public void markUnknown(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setStatus(PaymentStatus.UNKNOWN);
        payment.setUpdatedAt(LocalDateTime.now());
    }
}

public record PaymentPendingResult(Long paymentId, String orderNumber, Long amount) {}
```

---

## 2-3. Unknown State 복구 메커니즘

### PG 조회 클라이언트

```java
@Component
@RequiredArgsConstructor
public class PgClient {

    private final RestTemplate restTemplate;

    @Value("${pg.base-url}")
    private String pgBaseUrl;

    // 결제 승인 요청
    public PgApproveResponse approve(String orderNumber, Long amount) {
        // ... Phase 1과 동일
    }

    /**
     * PG사에 결제 상태를 조회한다.
     * Unknown State에 빠진 결제의 실제 승인 여부를 확인하는 데 사용한다.
     */
    public PgQueryResponse queryPayment(String orderNumber) {
        ResponseEntity<PgQueryResponse> response = restTemplate.getForEntity(
            pgBaseUrl + "/v1/payments/orders/" + orderNumber,
            PgQueryResponse.class
        );
        return response.getBody();
    }
}

public record PgQueryResponse(
    String paymentKey,
    String orderId,
    String status,        // "DONE", "CANCELED", "WAITING_FOR_DEPOSIT", etc.
    Long totalAmount
) {}
```

### WireMock — PG 조회 API 응답

```json
// wiremock/mappings/pg-query-success.json
{
  "request": {
    "method": "GET",
    "urlPattern": "/v1/payments/orders/.*"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "paymentKey": "pk_test_abc123",
      "orderId": "ORD-001",
      "status": "DONE",
      "totalAmount": 10000
    }
  }
}
```

### Unknown State 복구 스케줄러

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class UnknownPaymentRecoveryScheduler {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PgClient pgClient;
    private final MeterRegistry meterRegistry;

    /**
     * 1분마다 UNKNOWN 상태의 결제를 조회하여 실제 상태를 확인한다.
     */
    @Scheduled(fixedDelay = 60000)  // 1분 주기
    @Transactional
    public void recoverUnknownPayments() {
        List<Payment> unknownPayments = paymentRepository.findByStatus(PaymentStatus.UNKNOWN);

        for (Payment payment : unknownPayments) {
            try {
                Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
                PgQueryResponse pgStatus = pgClient.queryPayment(order.getOrderNumber());

                if ("DONE".equals(pgStatus.status())) {
                    // PG에서 실제로 승인됨 → SUCCESS로 변경
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setPgPaymentKey(pgStatus.paymentKey());
                    order.setStatus(OrderStatus.SUCCESS);
                    log.info("UNKNOWN → SUCCESS 복구: {}", order.getOrderNumber());
                    meterRegistry.counter("payment.recovery.success").increment();
                } else {
                    // PG에서 승인되지 않음 → FAILED로 변경
                    payment.setStatus(PaymentStatus.FAILED);
                    order.setStatus(OrderStatus.FAILED);
                    log.info("UNKNOWN → FAILED 복구: {}", order.getOrderNumber());
                    meterRegistry.counter("payment.recovery.failed").increment();
                }

                payment.setUpdatedAt(LocalDateTime.now());
                order.setUpdatedAt(LocalDateTime.now());

            } catch (Exception e) {
                // 조회 자체도 실패 — 다음 주기에 재시도
                log.warn("UNKNOWN 결제 복구 실패 (다음 주기 재시도): paymentId={}", payment.getId(), e);
                meterRegistry.counter("payment.recovery.retry").increment();
            }
        }
    }
}
```

### Repository 추가 메서드

```java
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    long countByOrderId(Long orderId);
    List<Payment> findByStatus(PaymentStatus status);
}
```

---

## 2-4. Controller 업데이트

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResult> processPayment(@RequestBody PaymentRequest request) {
        PaymentResult result = paymentService.processPayment(request.orderNumber());

        return switch (result.status()) {
            case "SUCCESS" -> ResponseEntity.ok(result);
            case "UNKNOWN" -> ResponseEntity.status(HttpStatus.ACCEPTED)  // 202: 처리 중
                .body(result);
            case "FAILED" -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)  // 402
                .body(result);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(result);
        };
    }

    /**
     * 결제 상태 조회 API
     * 클라이언트가 UNKNOWN 응답을 받았을 때 폴링으로 최종 상태를 확인하는 데 사용.
     */
    @GetMapping("/{orderNumber}/status")
    public ResponseEntity<PaymentStatusResponse> getPaymentStatus(@PathVariable String orderNumber) {
        PaymentStatusResponse status = paymentService.getPaymentStatus(orderNumber);
        return ResponseEntity.ok(status);
    }
}

public record PaymentStatusResponse(String orderNumber, String orderStatus, String paymentStatus) {}
```

---

## 2-5. TDD — Unknown State 검증

### 테스트 1: 커넥션 풀 고갈 해소 확인

```java
@Test
@DisplayName("TX 분리 후 PG 응답이 10초 걸려도 커넥션 풀이 고갈되지 않는다")
void txSeparation_prevents_connection_pool_exhaustion() throws InterruptedException {
    // WireMock: PG 응답 10초 지연
    wireMock.stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"paymentKey":"pk_test","orderId":"ORD-001","status":"DONE","totalAmount":10000}
                """)
            .withFixedDelay(10000)));

    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 1; i <= threadCount; i++) {
        String orderNumber = "ORD-" + String.format("%03d", i);
        executor.submit(() -> {
            try {
                ResponseEntity<PaymentResult> response = restTemplate.postForEntity(
                    "/api/payments",
                    new PaymentRequest(orderNumber),
                    PaymentResult.class
                );
                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount.incrementAndGet();
                }
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await(60, TimeUnit.SECONDS);

    // TX 분리 덕분에 PG 대기 중에도 커넥션을 점유하지 않으므로
    // Phase 1과 달리 커넥션 타임아웃이 발생하지 않아야 한다
    System.out.println("TX 분리 후 성공: " + successCount.get() + " / " + threadCount);
    assertThat(successCount.get()).isEqualTo(threadCount);
}
```

### 테스트 2: Read Timeout 시 Unknown State 진입

```java
@Test
@DisplayName("PG 응답 타임아웃 시 결제가 UNKNOWN 상태로 마킹된다")
void readTimeout_causes_unknown_state() {
    // WireMock: 응답을 아예 안 보냄 (ReadTimeout 유발)
    wireMock.stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
        .willReturn(aResponse()
            .withFixedDelay(35000)));  // RestTemplate readTimeout(30s)보다 김

    ResponseEntity<PaymentResult> response = restTemplate.postForEntity(
        "/api/payments",
        new PaymentRequest("ORD-TIMEOUT"),
        PaymentResult.class
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);  // 202
    assertThat(response.getBody().status()).isEqualTo("UNKNOWN");

    // DB에서 UNKNOWN 상태 확인
    Payment payment = paymentRepository.findByStatus(PaymentStatus.UNKNOWN).get(0);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

    System.out.println("=== Unknown State 테스트 결과 ===");
    System.out.println("HTTP 응답: 202 ACCEPTED");
    System.out.println("DB 결제 상태: UNKNOWN");
    System.out.println("→ PG에 돈이 빠졌는지 여부를 알 수 없는 상태!");
}
```

### 테스트 3: Unknown State 복구 스케줄러 동작

```java
@Test
@DisplayName("UNKNOWN 상태의 결제가 PG 조회를 통해 SUCCESS로 복구된다")
void unknownPayment_recovers_via_pg_query() {
    // 1. 의도적으로 UNKNOWN 상태 생성
    Payment unknownPayment = new Payment(order.getId(), 10000L);
    unknownPayment.setStatus(PaymentStatus.UNKNOWN);
    paymentRepository.save(unknownPayment);

    // 2. WireMock: PG 조회 API가 "승인됨" 반환
    wireMock.stubFor(get(urlPathMatching("/v1/payments/orders/.*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"paymentKey":"pk_recovered","orderId":"ORD-001","status":"DONE","totalAmount":10000}
                """)));

    // 3. 복구 스케줄러 수동 실행
    recoveryScheduler.recoverUnknownPayments();

    // 4. 검증: UNKNOWN → SUCCESS
    Payment recovered = paymentRepository.findById(unknownPayment.getId()).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    assertThat(recovered.getPgPaymentKey()).isEqualTo("pk_recovered");

    System.out.println("=== UNKNOWN 복구 테스트 ===");
    System.out.println("복구 전: UNKNOWN");
    System.out.println("PG 조회 결과: DONE");
    System.out.println("복구 후: SUCCESS (pk_recovered)");
}
```

---

## 2-6. k6 부하 테스트

```javascript
// scripts/phase2-tx-separation.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const statusSuccess = new Counter("payment_status_success");
const statusUnknown = new Counter("payment_status_unknown");
const statusFailed = new Counter("payment_status_failed");

export const options = {
  scenarios: {
    normal: {
      executor: "constant-vus",
      vus: 50,
      duration: "30s",
    },
  },
};

export default function () {
  const orderNumber = `ORD-${__VU}-${__ITER}`;

  const res = http.post(
    "http://host.docker.internal:8080/api/payments",
    JSON.stringify({ orderNumber: orderNumber }),
    { headers: { "Content-Type": "application/json" } },
  );

  if (res.status === 200) statusSuccess.add(1);
  else if (res.status === 202) statusUnknown.add(1);
  else statusFailed.add(1);

  check(res, {
    "not 5xx": (r) => r.status < 500,
  });

  sleep(0.1);
}

// → Phase 1의 동일 시나리오(50VU, PG 10초 지연)와 비교한다.
// → 커넥션 타임아웃이 0건인 것을 확인한다.
```

---

## 2-7. Grafana 모니터링 패널

### 이 Phase에서 추가로 관찰할 패널

```
Row 1: 결제 상태 분포 (커스텀 메트릭)
  - payment.state.pending   (PENDING 진입 건수)
  - payment.state.success   (SUCCESS 완료 건수)
  - payment.state.unknown   (Unknown State 진입 건수)
  - payment.state.failed    (FAILED 건수)

Row 2: Unknown State 복구
  - payment.recovery.success  (복구 성공)
  - payment.recovery.failed   (PG 미승인 확인)
  - payment.recovery.retry    (복구 시도 실패 — 다음 주기 재시도)

Row 3: Phase 1과 비교
  - hikaricp_connections_active  (풀 고갈이 발생하지 않는 것 확인)
  - hikaricp_connections_pending (0에 가까운 것 확인)
```

### PromQL

```promql
# UNKNOWN 누적 건수
payment_state_unknown_total

# PENDING 평균 체류 시간 (커스텀 타이머 필요 시)
# → 사실상 PG 응답 시간 ≈ PENDING 체류 시간
histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{uri="/v1/payments/confirm"}[5m]))
```

---

## Phase 1과의 비교 측정표

| 항목 | Phase 1 (나이브) | Phase 2 (TX 분리) |
|------|-----------------|-------------------|
| 커넥션 풀 고갈 | 발생 (N초 만에) | **해소** |
| PG 지연 시 시스템 영향 | 전체 먹통 | PG 대기만 느림, 다른 API 정상 |
| 데이터 불일치 | PG 성공 + DB 롤백 → 돈 증발 | PENDING 상태로 기록됨 |
| Unknown State | 고려하지 않음 | UNKNOWN 마킹 + 복구 스케줄러 |
| 복구 메커니즘 | 없음 | PG 조회 API polling |

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: Unknown State 발생률 ***%, PENDING 평균 체류 시간 ***ms, 복구 성공률 ***%

---

## ❓ 남은 문제 → Phase 3로

> "타임아웃 후 재시도했더니 결제가 두 번 됐다. 사용자가 결제 버튼을 따닥 눌러서 동시에 두 건이 들어왔다."

→ [[phase3-idempotency]]
