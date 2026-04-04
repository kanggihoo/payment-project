# Phase 3. 멱등성(Idempotency) 보장 + TossPayments Sandbox

> "같은 결제 요청을 100번 보내도 딱 1번만 처리되어야 한다."

---

## 이 Phase의 목표

- Phase 2에서 해결하지 못한 **중복 결제** 문제를 멱등성으로 방어한다
- DB `UNIQUE INDEX`와 `Redis SET NX` 두 가지 방식을 구현하고 비교한다
- WireMock에서 **TossPayments Sandbox**로 교체하여 실제 PG 동작을 검증한다

---

## 이전 Phase의 문제를 어떻게 해결하는가

```
시나리오 1: 타임아웃 후 재시도
  사용자가 결제 요청 → 타임아웃(UNKNOWN) → 재시도 → PG에 두 번째 승인 요청
  → 돈이 두 번 빠짐!

시나리오 2: 따닥 클릭
  사용자가 결제 버튼을 0.1초 간격으로 두 번 클릭
  → 동시에 두 건의 결제 요청이 서버에 도착
  → 두 건 모두 PG 승인 → 이중 결제!
```

**해결**: 요청마다 고유한 `Idempotency-Key`를 부여하고, 이미 처리된 키는 차단한다.

---

## 3-1. 인프라 변경

### Docker Compose 추가 — Redis

```yaml
# docker-compose.app.yml에 추가
  redis:
    image: redis:7.2-alpine
    ports:
      - "6379:6379"
```

### build.gradle 추가

```gradle
// Redis 의존성 추가
implementation 'org.springframework.boot:spring-boot-starter-data-redis'

// Testcontainers Redis
testImplementation 'com.redis:testcontainers-redis:2.2.2'
```

### application.yml 변경

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

pg:
  base-url: https://api.tosspayments.com   # WireMock → TossPayments Sandbox 교체
  secret-key: test_sk_xxxxxxxxxxxxxxxx      # TossPayments 테스트 시크릿 키
```

---

## 3-2. 멱등성 키 설계

### 전략: 클라이언트가 발급, 서버가 검증

```
클라이언트 → POST /api/payments
             Headers: { Idempotency-Key: "order-123-uuid-abc" }
             Body:    { orderNumber: "ORD-001" }

서버:
  1. Idempotency-Key로 "이미 처리된 요청"인지 확인
  2. 이미 있으면 → 기존 결과 반환 (PG 재호출 X)
  3. 없으면 → 키 등록 + 결제 진행
```

### DB 기반 멱등성 (방법 A)

```sql
-- 멱등성 키 테이블
CREATE TABLE idempotency_key (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,  -- 고유 키
    payment_id      BIGINT REFERENCES payment(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    -- PROCESSING: 처리 중 (락 역할)
    -- COMPLETED: 처리 완료
    response_body   TEXT,                          -- 캐싱된 응답
    created_at      TIMESTAMP DEFAULT NOW(),
    expires_at      TIMESTAMP DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE UNIQUE INDEX idx_idempotency_key ON idempotency_key(idempotency_key);
```

### Redis 기반 멱등성 (방법 B)

```java
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * 멱등성 키 획득 시도.
     * Redis SET NX (존재하지 않을 때만 설정) — 원자적 연산.
     *
     * @return true: 첫 번째 요청 (처리 진행), false: 중복 요청 (거절)
     */
    public boolean tryAcquire(String idempotencyKey) {
        Boolean result = redisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + idempotencyKey, "PROCESSING", TTL);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 처리 완료 시 결과를 캐싱한다.
     * 이후 동일 키 요청에 대해 캐싱된 결과를 반환한다.
     */
    public void markCompleted(String idempotencyKey, String responseJson) {
        redisTemplate.opsForValue()
            .set(KEY_PREFIX + idempotencyKey, responseJson, TTL);
    }

    /**
     * 이미 완료된 요청의 캐싱된 응답을 조회한다.
     */
    public Optional<String> getExistingResponse(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (value == null || "PROCESSING".equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 처리 실패 시 키를 제거하여 재시도를 허용한다.
     */
    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}
```

---

## 3-3. Service에 멱등성 적용

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentInternalService internalService;
    private final PgClient pgClient;
    private final RedisIdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentResult processPayment(String orderNumber, String idempotencyKey) {
        // 1. 멱등성 키로 중복 요청 확인
        Optional<String> existingResponse = idempotencyStore.getExistingResponse(idempotencyKey);
        if (existingResponse.isPresent()) {
            meterRegistry.counter("payment.duplicate.returned_cached").increment();
            return deserialize(existingResponse.get());
        }

        // 2. 키 획득 시도 (SET NX)
        boolean acquired = idempotencyStore.tryAcquire(idempotencyKey);
        if (!acquired) {
            // 다른 스레드가 이미 처리 중 → 중복 요청으로 거절
            meterRegistry.counter("payment.duplicate.blocked").increment();
            throw new DuplicatePaymentException("이미 처리 중인 결제입니다: " + idempotencyKey);
        }

        try {
            // 3. 실제 결제 처리 (Phase 2의 TX 분리 로직)
            PaymentPendingResult pending = internalService.createPendingPayment(orderNumber);

            PgApproveResponse pgResponse = pgClient.approve(
                pending.orderNumber(), pending.amount()
            );

            internalService.completePayment(pending.paymentId(), pgResponse.paymentKey());

            PaymentResult result = new PaymentResult(orderNumber, "SUCCESS", pgResponse.paymentKey());

            // 4. 성공 결과를 캐싱 (동일 키로 재요청 시 이 결과를 반환)
            idempotencyStore.markCompleted(idempotencyKey, serialize(result));

            return result;

        } catch (Exception e) {
            // 실패 시 키 해제 → 재시도 허용
            idempotencyStore.release(idempotencyKey);
            throw e;
        }
    }

    private String serialize(PaymentResult result) {
        try { return objectMapper.writeValueAsString(result); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private PaymentResult deserialize(String json) {
        try { return objectMapper.readValue(json, PaymentResult.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

### Controller 업데이트

```java
@PostMapping
public ResponseEntity<PaymentResult> processPayment(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody PaymentRequest request) {
    try {
        PaymentResult result = paymentService.processPayment(
            request.orderNumber(), idempotencyKey
        );
        return ResponseEntity.ok(result);
    } catch (DuplicatePaymentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)  // 409
            .body(new PaymentResult(request.orderNumber(), "DUPLICATE", null));
    }
}
```

---

## 3-4. TossPayments Sandbox 연동

### PG 클라이언트를 TossPayments 용으로 교체

```java
@Component
@RequiredArgsConstructor
public class TossPaymentsClient implements PgClient {

    private final RestTemplate restTemplate;

    @Value("${pg.base-url}")
    private String baseUrl;

    @Value("${pg.secret-key}")
    private String secretKey;

    @Override
    public PgApproveResponse approve(String orderNumber, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // TossPayments 인증: Basic Auth (secretKey + ":")
        headers.setBasicAuth(secretKey, "");

        Map<String, Object> body = Map.of(
            "paymentKey", "test_pk_" + orderNumber,
            "orderId", orderNumber,
            "amount", amount
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<TossPaymentResponse> response = restTemplate.postForEntity(
            baseUrl + "/v1/payments/confirm",
            request,
            TossPaymentResponse.class
        );

        TossPaymentResponse toss = response.getBody();
        return new PgApproveResponse(
            toss.paymentKey(), toss.orderId(), toss.status(), toss.totalAmount()
        );
    }

    @Override
    public PgQueryResponse queryPayment(String orderNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(secretKey, "");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<TossPaymentResponse> response = restTemplate.exchange(
            baseUrl + "/v1/payments/orders/" + orderNumber,
            HttpMethod.GET,
            request,
            TossPaymentResponse.class
        );

        TossPaymentResponse toss = response.getBody();
        return new PgQueryResponse(
            toss.paymentKey(), toss.orderId(), toss.status(), toss.totalAmount()
        );
    }
}

public record TossPaymentResponse(
    String paymentKey,
    String orderId,
    String status,
    Long totalAmount,
    String approvedAt
) {}
```

> **WireMock은 통합 테스트에서 계속 사용**한다. TossPayments Sandbox는 수동 확인 및 E2E 테스트용.

---

## 3-5. TDD — 멱등성 검증

### 테스트 1: 동시 중복 요청 100건 → 1건만 통과

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdempotencyConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("payment")
        .withInitScript("init/01_schema.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", wireMock::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        // WireMock: PG 승인 성공
        wireMock.stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"paymentKey":"pk_test","orderId":"ORD-001","status":"DONE","totalAmount":10000}
                    """)));
    }

    @Test
    @DisplayName("동일 멱등성 키로 100건 동시 요청 시 1건만 PG로 전달되고 99건은 차단된다")
    void concurrent_duplicate_requests_blocked_by_idempotency() throws InterruptedException {
        String idempotencyKey = "idem-key-" + UUID.randomUUID();
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Idempotency-Key", idempotencyKey);
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    HttpEntity<PaymentRequest> request = new HttpEntity<>(
                        new PaymentRequest("ORD-001"), headers
                    );

                    ResponseEntity<PaymentResult> response = restTemplate.postForEntity(
                        "/api/payments", request, PaymentResult.class
                    );

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        System.out.println("=== 멱등성 동시성 테스트 결과 ===");
        System.out.println("성공 (PG 호출): " + successCount.get());
        System.out.println("중복 차단: " + duplicateCount.get());
        System.out.println("기타 에러: " + errorCount.get());

        // 정확히 1건만 PG에 요청이 가야 한다
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);

        // WireMock: PG 호출이 정확히 1건인지 확인
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));
    }
}
```

### 테스트 2: 동일 키로 재시도 시 캐싱된 결과 반환

```java
@Test
@DisplayName("이미 성공한 결제를 같은 키로 재시도하면 캐싱된 결과가 반환된다")
void retry_with_same_key_returns_cached_response() {
    String idempotencyKey = "idem-key-retry-" + UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("Idempotency-Key", idempotencyKey);
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<PaymentRequest> request = new HttpEntity<>(
        new PaymentRequest("ORD-002"), headers
    );

    // 1차 요청: 성공
    ResponseEntity<PaymentResult> first = restTemplate.postForEntity(
        "/api/payments", request, PaymentResult.class
    );
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    // 2차 요청: 동일 키로 재시도
    ResponseEntity<PaymentResult> second = restTemplate.postForEntity(
        "/api/payments", request, PaymentResult.class
    );
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().paymentKey()).isEqualTo(first.getBody().paymentKey());

    // PG 호출은 1번만 발생해야 한다
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));

    System.out.println("=== 재시도 캐싱 테스트 ===");
    System.out.println("1차 결과: " + first.getBody());
    System.out.println("2차 결과: " + second.getBody());
    System.out.println("PG 호출 횟수: 1 (캐싱 동작 확인)");
}
```

---

## 3-6. k6 부하 테스트

```javascript
// scripts/phase3-idempotency.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const duplicateBlocked = new Counter("duplicate_blocked");
const paymentSuccess = new Counter("payment_success_unique");

export const options = {
  scenarios: {
    duplicate_attack: {
      executor: "constant-vus",
      vus: 100,  // 같은 키로 100명이 동시에 요청
      duration: "10s",
    },
  },
};

const FIXED_IDEMPOTENCY_KEY = "load-test-fixed-key-001";

export default function () {
  const res = http.post(
    "http://host.docker.internal:8080/api/payments",
    JSON.stringify({ orderNumber: "ORD-LOAD-TEST" }),
    {
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": FIXED_IDEMPOTENCY_KEY,  // 모든 VU가 같은 키 사용
      },
    },
  );

  if (res.status === 200) paymentSuccess.add(1);
  if (res.status === 409) duplicateBlocked.add(1);

  check(res, {
    "200 or 409": (r) => r.status === 200 || r.status === 409,
  });

  sleep(0.1);
}

// → 10초간 100VU가 같은 키로 요청했을 때
// → payment_success_unique는 1건이어야 하고
// → duplicate_blocked가 나머지 전부여야 한다
```

---

## 3-7. Grafana 모니터링 패널

### 추가 패널

```
Row 1: 멱등성 메트릭
  - payment.duplicate.blocked        (중복 차단 건수 — 이 값이 높을수록 멱등성이 잘 동작)
  - payment.duplicate.returned_cached (캐싱 결과 반환 건수)

Row 2: Redis 멱등성 키 상태
  - Redis key 개수 추이 (redis_db_keys)
  - Redis 명령 처리 시간 (redis_commands_duration_seconds)
```

---

## Phase 2와의 비교 측정표

| 항목 | Phase 2 (TX 분리) | Phase 3 (멱등성) |
|------|-------------------|-----------------|
| 중복 결제 방지 | 없음 | ✅ Idempotency-Key |
| 따닥 클릭 시 | 두 건 모두 PG 호출 | 1건만 PG 호출, 99건 차단 |
| 타임아웃 재시도 시 | 새 결제로 처리 | 캐싱된 결과 반환 |
| 추가 인프라 | 없음 | Redis (또는 DB UNIQUE INDEX) |

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: 100건 동시 중복 중 PG 호출 ***건, 차단 ***건, 멱등성 검증 추가 지연 ***ms

---

## ❓ 남은 문제 → Phase 4로

> "결제 하나만 처리할 때는 괜찮다. 결제 + 쿠폰 차감 + 포인트 사용을 함께 처리하다가 중간에 실패하면?"

→ [[phase4-compensating-tx]]
