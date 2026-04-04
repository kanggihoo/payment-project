# Phase 1. 베이스라인 — 나이브한 결제

> "외부 API 호출을 `@Transactional` 안에 넣으면 어떤 참사가 발생하는가?"

---

## 이 Phase의 목표

- 가장 직관적인 형태의 결제 로직을 작성한다
- `@Transactional` 안에서 외부 API를 호출했을 때 발생하는 **두 가지 참사**를 직접 수치로 확인한다
  1. DB 커넥션 풀 고갈
  2. 데이터 불일치 (PG에서 돈은 빠졌는데 DB는 롤백)

---

## 1-1. 인프라 구성

### Docker Compose — 애플리케이션 스택

```yaml
# docker-compose.app.yml
services:
  postgres:
    image: postgres:17-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: payment
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
    volumes:
      - ./postgres/init/01_schema.sql:/docker-entrypoint-initdb.d/01_schema.sql

  postgres-exporter:
    image: prometheuscommunity/postgres-exporter
    ports:
      - "9187:9187"
    environment:
      DATA_SOURCE_NAME: "postgresql://user:password@postgres:5432/payment?sslmode=disable"
    depends_on:
      - postgres

  wiremock:
    image: wiremock/wiremock:latest
    ports:
      - "8089:8080"
    volumes:
      - ./wiremock:/home/wiremock
    command: --verbose
```

### Docker Compose — 모니터링 스택

```yaml
# docker-compose.monitoring.yml
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin

  k6:
    image: grafana/k6
    volumes:
      - ./scripts:/scripts
    depends_on:
      - prometheus
    environment:
      - K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write
    command: run --out experimental-prometheus-rw /scripts/phase1-baseline.js
    extra_hosts:
      - "host.docker.internal:host-gateway"
    profiles:
      - k6
```

### Prometheus 설정

```yaml
# prometheus.yml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: "spring"
    static_configs:
      - targets: ["host.docker.internal:8080"]
    metrics_path: "/actuator/prometheus"

  - job_name: "postgres"
    static_configs:
      - targets: ["postgres-exporter:9187"]
```

### 실행 명령어

```bash
# 1. 애플리케이션 인프라 (PostgreSQL + WireMock)
docker compose -f docker-compose.app.yml up -d

# 2. 모니터링 (Prometheus + Grafana)
docker compose -f docker-compose.monitoring.yml up -d

# 3. Spring Boot 실행 (로컬)
./gradlew bootRun

# 4. k6 부하 테스트 실행
docker compose -f docker-compose.monitoring.yml \
  --profile k6 run --rm \
  k6 run --out experimental-prometheus-rw /scripts/phase1-baseline.js
```

---

## 1-2. DB 스키마

```sql
-- postgres/init/01_schema.sql

-- 주문 테이블
CREATE TABLE orders (
    id             BIGSERIAL PRIMARY KEY,
    order_number   VARCHAR(50) NOT NULL UNIQUE,  -- 주문번호 (외부 노출용)
    product_name   VARCHAR(255) NOT NULL,
    amount         BIGINT NOT NULL,              -- 결제 금액 (원 단위)
    status         VARCHAR(20) NOT NULL DEFAULT 'READY',
    -- READY: 주문 생성됨
    -- SUCCESS: 결제 완료
    -- FAILED: 결제 실패
    created_at     TIMESTAMP DEFAULT NOW(),
    updated_at     TIMESTAMP DEFAULT NOW()
);

-- 결제 내역 테이블
CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id),
    pg_payment_key  VARCHAR(200),               -- PG사에서 발급한 결제 키
    amount          BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'READY',
    -- READY: 결제 대기
    -- SUCCESS: 결제 성공
    -- FAILED: 결제 실패
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 테스트 데이터
INSERT INTO orders (order_number, product_name, amount, status)
VALUES ('ORD-001', '테스트 상품', 10000, 'READY');
```

---

## 1-3. WireMock 설정 — 가상 PG사

### 정상 응답 (기본)

```json
// wiremock/mappings/pg-approve-success.json
{
  "request": {
    "method": "POST",
    "urlPattern": "/v1/payments/confirm"
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
      "approvedAt": "2025-01-01T12:00:00+09:00",
      "totalAmount": 10000
    }
  }
}
```

### 지연 응답 (커넥션 풀 고갈 유발용)

```json
// wiremock/mappings/pg-approve-slow.json
{
  "request": {
    "method": "POST",
    "urlPattern": "/v1/payments/confirm"
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
    },
    "fixedDelayMilliseconds": 10000
  }
}
```

> WireMock 매핑을 교체하면서 테스트한다. 정상 응답 → 지연 응답으로 교체하면 커넥션 풀 고갈을 바로 확인할 수 있다.

---

## 1-4. 나이브한 구현 — 모든 것을 @Transactional 안에

### Entity

```java
@Entity
@Table(name = "orders")
@Getter @Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.READY;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Order(String orderNumber, String productName, Long amount) {
        this.orderNumber = orderNumber;
        this.productName = productName;
        this.amount = amount;
    }
}

public enum OrderStatus {
    READY, SUCCESS, FAILED
}
```

```java
@Entity
@Table(name = "payment")
@Getter @Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    private String pgPaymentKey;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.READY;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Payment(Long orderId, Long amount) {
        this.orderId = orderId;
        this.amount = amount;
    }
}

public enum PaymentStatus {
    READY, SUCCESS, FAILED
}
```

### Repository

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);
}

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    long countByOrderId(Long orderId);
}
```

### PG 클라이언트 — RestTemplate 기반

```java
@Component
@RequiredArgsConstructor
public class PgClient {

    private final RestTemplate restTemplate;

    @Value("${pg.base-url}")
    private String pgBaseUrl;

    /**
     * PG사에 결제 승인을 요청한다.
     * 이 호출이 @Transactional 안에 있으면 DB 커넥션을 쥔 채 대기하게 된다.
     */
    public PgApproveResponse approve(String orderNumber, Long amount) {
        PgApproveRequest request = new PgApproveRequest(orderNumber, amount);

        ResponseEntity<PgApproveResponse> response = restTemplate.postForEntity(
            pgBaseUrl + "/v1/payments/confirm",
            request,
            PgApproveResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new PgPaymentException("PG 승인 실패: " + response.getStatusCode());
        }

        return response.getBody();
    }
}
```

```java
// DTO
public record PgApproveRequest(String orderId, Long amount) {}

public record PgApproveResponse(
    String paymentKey,
    String orderId,
    String status,
    Long totalAmount
) {}
```

### Service — 문제의 나이브한 구현

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    /**
     * ⚠️ 나이브한 구현 — 이 코드에는 두 가지 치명적 문제가 있다.
     *
     * 문제 1: @Transactional 안에서 외부 API를 호출한다.
     *   → PG 응답이 10초 걸리면 DB 커넥션을 10초 동안 점유
     *   → HikariCP 풀(기본 10개)이 금방 고갈 → 전체 시스템 정지
     *
     * 문제 2: PG 승인 성공 후 DB 저장 실패 시 트랜잭션이 롤백된다.
     *   → PG에서는 돈이 빠졌는데, 우리 DB에는 기록이 없다.
     *   → 고객 돈 증발 (데이터 불일치)
     */
    @Transactional
    public PaymentResult processPayment(String orderNumber) {
        // 1. 주문 조회
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderNumber));

        // 2. PG사에 결제 승인 요청 (⚠️ 트랜잭션 안에서 외부 HTTP 호출!)
        PgApproveResponse pgResponse = pgClient.approve(order.getOrderNumber(), order.getAmount());

        // 3. 결제 내역 저장
        Payment payment = new Payment(order.getId(), order.getAmount());
        payment.setPgPaymentKey(pgResponse.paymentKey());
        payment.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(payment);

        // 4. 주문 상태 업데이트
        order.setStatus(OrderStatus.SUCCESS);
        order.setUpdatedAt(LocalDateTime.now());

        return new PaymentResult(order.getOrderNumber(), "SUCCESS", pgResponse.paymentKey());
    }
}

public record PaymentResult(String orderNumber, String status, String paymentKey) {}
```

### Controller

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResult> processPayment(@RequestBody PaymentRequest request) {
        try {
            PaymentResult result = paymentService.processPayment(request.orderNumber());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PaymentResult(request.orderNumber(), "FAILED", null));
        }
    }
}

public record PaymentRequest(String orderNumber) {}
```

### Spring 설정

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payment
    username: user
    password: password
    hikari:
      maximum-pool-size: 10    # 기본값 — 이 작은 풀이 금방 고갈됨을 확인
      connection-timeout: 30000
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true

pg:
  base-url: http://localhost:8089  # WireMock

management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

### RestTemplate 설정

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))  // PG 응답 대기 최대 30초
            .build();
    }
}
```

---

## 1-5. TDD — 문제를 테스트로 증명

### 테스트 1: 커넥션 풀 고갈 확인

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConnectionPoolExhaustionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("payment")
        .withUsername("user")
        .withPassword("password")
        .withInitScript("init/01_schema.sql");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    // WireMock으로 PG 응답을 10초 지연시킨다
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void setUp() {
        // 테스트용 주문 데이터 생성
        for (int i = 1; i <= 20; i++) {
            Order order = new Order("ORD-" + String.format("%03d", i), "테스트 상품", 10000L);
            orderRepository.save(order);
        }

        // WireMock: PG 응답을 10초 지연
        wireMock.stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"paymentKey":"pk_test","orderId":"ORD-001","status":"DONE","totalAmount":10000}
                    """)
                .withFixedDelay(10000)));  // 10초 지연
    }

    @Test
    @DisplayName("PG 응답이 10초 지연되면 커넥션 풀(5개)이 고갈되어 후속 요청이 실패한다")
    void connectionPool_exhaustion_when_pg_response_delayed() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

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
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    if (e.getMessage().contains("Connection is not available")) {
                        timeoutCount.incrementAndGet();
                    }
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);

        System.out.println("=== 커넥션 풀 고갈 테스트 결과 ===");
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("커넥션 타임아웃: " + timeoutCount.get());

        // 풀 크기(5)보다 많은 동시 요청(20)이 들어오면
        // PG 응답이 10초 걸리는 동안 풀이 고갈되어 일부 요청이 실패해야 한다
        assertThat(failCount.get()).isGreaterThan(0);
    }
}
```

### 테스트 2: 데이터 불일치 확인 (PG 성공 → DB 실패)

```java
@SpringBootTest
@Testcontainers
class DataInconsistencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("payment")
        .withUsername("user")
        .withPassword("password")
        .withInitScript("init/01_schema.sql");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        orderRepository.save(new Order("ORD-INCONSISTENCY", "테스트 상품", 10000L));

        // WireMock: PG 승인 성공 응답
        wireMock.stubFor(post(urlPathEqualTo("/v1/payments/confirm"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"paymentKey":"pk_real_charged","orderId":"ORD-INCONSISTENCY","status":"DONE","totalAmount":10000}
                    """)));
    }

    @Test
    @DisplayName("PG 승인 성공 후 DB 저장 시 예외가 발생하면 돈은 빠졌는데 DB에는 기록이 없다")
    void pg_success_but_db_failure_causes_data_inconsistency() {
        // PG 호출은 성공하지만, DB 저장 시 예외를 발생시키는 시나리오
        // (실제로는 PaymentRepository.save() 시점에 DataIntegrityViolationException 등)

        // 이 테스트의 핵심: PG가 "DONE"을 응답했다는 것은
        // 고객의 카드에서 돈이 이미 빠져나갔다는 의미다.
        // 트랜잭션이 롤백되면 우리 DB에는 아무 기록이 없다.

        // WireMock 호출 로그를 확인하여 PG에 실제로 요청이 갔는지 검증
        wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));

        // DB에는 결제 기록이 없음을 확인
        long paymentCount = paymentRepository.countByOrderId(
            orderRepository.findByOrderNumber("ORD-INCONSISTENCY").orElseThrow().getId()
        );

        System.out.println("=== 데이터 불일치 시뮬레이션 ===");
        System.out.println("PG 승인: 성공 (pk_real_charged)");
        System.out.println("DB 결제 기록: " + paymentCount + "건");
        System.out.println("→ 고객 돈은 빠졌는데 우리 DB에 기록 없음 = 정합성 파괴!");
    }
}
```

---

## 1-6. k6 부하 테스트 스크립트

### 시나리오 1: 기본 동시 부하 (50 VU, 30초)

```javascript
// scripts/phase1-baseline.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const paymentSuccess = new Counter("payment_success");
const paymentFailed = new Counter("payment_failed");
const paymentDuration = new Trend("payment_duration");

export const options = {
  scenarios: {
    baseline: {
      executor: "constant-vus",
      vus: 50,
      duration: "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],       // 에러율 1% 미만
    http_req_duration: ["p(95)<5000"],     // p95 5초 미만
  },
};

export default function () {
  const orderNumber = `ORD-${__VU}-${__ITER}`;

  const res = http.post(
    "http://host.docker.internal:8080/api/payments",
    JSON.stringify({ orderNumber: orderNumber }),
    { headers: { "Content-Type": "application/json" } },
  );

  paymentDuration.add(res.timings.duration);

  const success = check(res, {
    "status 200": (r) => r.status === 200,
    "has paymentKey": (r) => {
      const body = JSON.parse(r.body);
      return body.paymentKey !== null;
    },
  });

  if (success) {
    paymentSuccess.add(1);
  } else {
    paymentFailed.add(1);
  }

  sleep(0.1);
}
```

### 시나리오 2: PG 지연 상태에서 부하 (커넥션 풀 고갈 관찰)

```javascript
// scripts/phase1-slow-pg.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const connectionTimeout = new Counter("connection_timeout");
const pgTimeout = new Counter("pg_timeout");

export const options = {
  scenarios: {
    slow_pg: {
      executor: "constant-vus",
      vus: 50,            // 커넥션 풀(10)보다 훨씬 많은 동시 사용자
      duration: "60s",
    },
  },
};

export default function () {
  const orderNumber = `ORD-${__VU}-${__ITER}`;

  const res = http.post(
    "http://host.docker.internal:8080/api/payments",
    JSON.stringify({ orderNumber: orderNumber }),
    {
      headers: { "Content-Type": "application/json" },
      timeout: "35s",
    },
  );

  check(res, { "status 200": (r) => r.status === 200 });

  // 에러 유형 분류
  if (res.status === 500) {
    connectionTimeout.add(1);
  }
  if (res.timings.duration > 10000) {
    pgTimeout.add(1);
  }

  sleep(0.1);
}

// → WireMock을 10초 지연으로 설정한 뒤 이 스크립트를 실행한다.
// → Grafana에서 hikaricp_connections_active가 최대치(10)에 도달하고
//   hikaricp_connections_pending이 급등하는 시점을 관찰한다.
```

---

## 1-7. 정합성 검증 쿼리

테스트 후 실행하여 불일치를 확인한다.

```sql
-- PG에 요청이 갔지만 DB에 기록이 없는 건 확인
-- (WireMock의 request journal과 대조)
SELECT
    o.order_number,
    o.status AS order_status,
    p.status AS payment_status,
    p.pg_payment_key
FROM orders o
LEFT JOIN payment p ON o.id = p.order_id
WHERE o.status = 'READY'     -- 주문은 아직 READY인데
  AND p.id IS NOT NULL;       -- 결제 기록은 존재하는 비정상 상태

-- 주문 상태별 건수
SELECT status, COUNT(*) FROM orders GROUP BY status;

-- 결제 상태별 건수
SELECT status, COUNT(*) FROM payment GROUP BY status;
```

---

## 1-8. Grafana 모니터링 패널

### 이 Phase에서 관찰할 핵심 패널

```
Row 1: 커넥션 풀 상태
  - hikaricp_connections_active          (현재 사용 중인 커넥션)
  - hikaricp_connections_pending         (대기 중인 요청)
  - hikaricp_connections_max             (최대 풀 크기)
  - hikaricp_connections_timeout_total   (타임아웃으로 실패한 횟수)

Row 2: HTTP 성능
  - http_server_requests_seconds (p50, p95, p99)
  - http_server_requests 에러율 (status=5xx / total)

Row 3: k6 메트릭
  - k6_http_req_duration (p95)
  - payment_success / payment_failed 카운터
```

### PromQL 예시

```promql
# 커넥션 풀 사용률 (%)
hikaricp_connections_active / hikaricp_connections_max * 100

# 커넥션 대기 수 (이 값이 0보다 크면 풀 고갈 시작)
hikaricp_connections_pending

# HTTP 에러율
rate(http_server_requests_seconds_count{status=~"5.."}[1m])
/ rate(http_server_requests_seconds_count[1m]) * 100
```

---

## 1-9. 테스트 초기화 API

테스트를 반복 실행할 때 DB 상태를 초기화한다.

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
@Profile("!prod")  // 프로덕션 환경에서는 절대 노출하지 않는다
public class TestResetController {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    @PostMapping("/reset")
    public ResponseEntity<Void> resetForTest() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();

        // 기본 테스트 주문 재생성
        Order order = new Order("ORD-001", "테스트 상품", 10000L);
        orderRepository.save(order);

        return ResponseEntity.ok().build();
    }
}
```

---

## 여기서 얻는 인사이트

- 외부 API를 `@Transactional` 안에서 호출하면 **커넥션 풀이 고갈**된다
  - PG 응답 10초 × 풀 크기 10 = 10개 커넥션이 10초간 점유 → 11번째 요청부터 대기/실패
- PG 승인 성공 후 DB 저장에서 예외가 발생하면 **돈은 빠졌는데 기록은 없는** 상태가 된다
- 이것이 "외부 시스템과의 분산 트랜잭션"이 불가능한 근본적 이유

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: 커넥션 풀 고갈까지 ***초, 불일치 발생 ***건 / 총 요청의 ***%

---

## ❓ 남은 문제 → Phase 2로

> "트랜잭션을 분리하면 커넥션 고갈은 해결된다. 하지만 분리한 후 PG 호출 도중 타임아웃이 나면 결제가 성공한 건지 실패한 건지 알 수 없다."

→ [[phase2-tx-separation]]
