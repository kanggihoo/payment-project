# Phase 1: Naive Payment 구현 계획

> **목표:** `@Transactional` 안에서 외부 PG API를 호출했을 때 발생하는 두 가지 참사를 TDD로 먼저 증명하고, 그 테스트를 통과시키는 최소한의 구현을 작성한다.

---

## 확정된 선택사항

| 항목 | 결정 |
|------|------|
| Session/Redis 의존성 | 제거 (Phase 1 불필요) |
| WireMock | `org.wiremock:wiremock:3.12.1` (standalone) |
| DataInconsistency 테스트 방식 | **두 가지** 모두 구현 |
| 테스트 방식 | **TDD** — 테스트 먼저 작성 → Red → 구현 → Green |

---

## TDD 사이클 개요

```
Red   → 컴파일조차 안 되는 테스트 작성 (인터페이스 정의)
Green → 테스트를 통과시키는 최소 구현 작성
```

Phase 1은 "나이브한 구현의 문제를 증명"이 목적이므로,
테스트가 **실패(문제 발생)를 검증**하는 구조다:

- `ConnectionPoolExhaustionTest` → `failCount > 0` 이어야 PASS
- `DataInconsistencyWithSpyTest` → PG 호출됨 + DB 기록 없음 이어야 PASS
- `DataInconsistencyWithFkTest` → 예외 발생 + DB 기록 없음 이어야 PASS

---

## Step 1: build.gradle 수정

**파일:** `payment/build.gradle`

**제거:**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-session-jdbc'
testImplementation 'org.springframework.boot:spring-boot-starter-session-data-redis-test'
testImplementation 'org.springframework.boot:spring-boot-starter-session-jdbc-test'
```

**추가:**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
testImplementation 'org.wiremock:wiremock:3.12.1'
```

---

## Step 2: TestcontainersConfiguration 수정

**파일:** `payment/src/test/java/com/example/payment/TestcontainersConfiguration.java`

변경 내용:
- Redis `GenericContainer` bean 제거
- PostgreSQL 이미지 `postgres:latest` → `postgres:17-alpine`
- `.withInitScript("init/01_schema.sql")` 추가 (`ddl-auto: validate` 대응)

---

## Step 3: 인프라 파일 생성

모두 프로젝트 루트(`/Users/kkh/Desktop/payment-project/`)에 생성.

| 파일 | 내용 |
|------|------|
| `docker-compose.app.yml` | PostgreSQL 17-alpine + postgres-exporter(9187) + WireMock(8089) |
| `docker-compose.monitoring.yml` | Prometheus + Grafana + k6 (`profiles: [k6]`) |
| `prometheus.yml` | scrape: Spring(8080/actuator/prometheus) + postgres-exporter(9187), interval: 5s |
| `wiremock/mappings/pg-approve-success.json` | POST `/v1/payments/confirm` → 200 정상 응답 |
| `wiremock/mappings/pg-approve-slow.json` | 동일 응답 + `fixedDelayMilliseconds: 10000` |
| `postgres/init/01_schema.sql` | orders + payment 테이블 DDL + 시드 데이터(ORD-001) |
| `scripts/phase1-baseline.js` | k6: 50 VUs, 30s, 정상 PG |
| `scripts/phase1-slow-pg.js` | k6: 50 VUs, 60s, timeout 35s, 느린 PG |

> **주의:** Prometheus 컨테이너 command에 `--web.enable-remote-write-receiver` 추가 필요 (k6 metrics 수신용)

---

## Step 4: 테스트 리소스

**파일:** `payment/src/test/resources/init/01_schema.sql`

`postgres/init/01_schema.sql`과 동일 내용.
Testcontainers의 `.withInitScript("init/01_schema.sql")`은 test classpath를 기준으로 로드한다.

---

## Step 5: application.yaml 수정

**파일:** `payment/src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: payment
  datasource:
    url: jdbc:postgresql://localhost:5432/payment
    username: user
    password: password
    hikari:
      maximum-pool-size: 10
      connection-timeout: 30000
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true

pg:
  base-url: http://localhost:8089

management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,metrics
  prometheus:
    metrics:
      export:
        enabled: true
```

---

## Step 6: TDD Red — 테스트 먼저 작성

> 이 단계에서 구현 클래스가 없으므로 컴파일이 실패한다. 컴파일 에러가 "Red" 상태다.

### 6-1. ConnectionPoolExhaustionTest

**파일:** `payment/src/test/java/com/example/payment/ConnectionPoolExhaustionTest.java`

```
시나리오: PG 응답이 10초 지연될 때, 커넥션 풀(5개)보다 많은 동시 요청(20개)이 들어오면
         일부 요청이 커넥션 타임아웃으로 실패한다.

설정:
  - PostgreSQLContainer (postgres:17-alpine, init script 포함)
  - @DynamicPropertySource: datasource URL/user/pass + hikari.maximum-pool-size=5
  - @RegisterExtension WireMockExtension: POST /v1/payments/confirm → 10초 지연 stub
  - TestcontainersConfiguration import 하지 않음 (Redis 불필요)
  - @Timeout(120) — 테스트 소요 시간 여유 확보

검증:
  assertThat(failCount.get()).isGreaterThan(0)
```

### 6-2. DataInconsistencyWithSpyTest

**파일:** `payment/src/test/java/com/example/payment/DataInconsistencyWithSpyTest.java`

```
시나리오: PG 승인은 성공했으나 PaymentRepository.save() 시점에 예외가 발생하면
         트랜잭션이 롤백되어 DB에 결제 기록이 남지 않는다.

설정:
  - PostgreSQLContainer + WireMockExtension (정상 응답 stub)
  - @TestConfiguration: PaymentRepository를 Mockito.spy()로 감싸고
    doThrow(DataIntegrityViolationException.class).when(spy).save(any()) 등록
  - @Primary로 spy bean이 실제 bean을 대체

검증:
  1. wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")))
     → PG에 승인 요청이 실제로 전송됨 (돈은 빠짐)
  2. paymentRepository.countByOrderId(orderId) == 0
     → DB에 결제 기록 없음 (롤백됨)
  3. paymentService.processPayment() 호출 시 예외 발생
```

### 6-3. DataInconsistencyWithFkTest

**파일:** `payment/src/test/java/com/example/payment/DataInconsistencyWithFkTest.java`

```
시나리오: 존재하지 않는 orderId로 Payment 저장을 시도하면
         FK 제약 위반으로 DataIntegrityViolationException이 발생하고
         DB에 아무 기록도 남지 않는다.

설정:
  - PostgreSQLContainer + WireMockExtension (정상 응답 stub)
  - Service를 호출하지 않고 PaymentRepository에 직접 corrupt Payment 저장 시도
    (payment.setOrderId(99999L) — 존재하지 않는 FK)

검증:
  1. assertThatThrownBy(() -> paymentRepository.saveAndFlush(corruptPayment))
       .isInstanceOf(DataIntegrityViolationException.class)
  2. paymentRepository.count() == 0
```

---

## Step 7: TDD Green — 구현 작성

테스트가 Red 상태를 확인한 후, 컴파일 및 테스트를 통과시키는 최소 구현을 작성한다.

생성 순서 (의존 방향 순):

### Domain
| 파일 | 내용 |
|------|------|
| `domain/OrderStatus.java` | `enum OrderStatus { READY, SUCCESS, FAILED }` |
| `domain/PaymentStatus.java` | `enum PaymentStatus { READY, SUCCESS, FAILED }` |
| `domain/Order.java` | `@Entity @Table(name="orders")` — Lombok @Getter @Setter @NoArgsConstructor |
| `domain/Payment.java` | `@Entity @Table(name="payment")` — orderId는 plain `Long` (naive, FK 관계 없음) |

> **주의:** `@Table(name = "orders")` 필수 — `ORDER`는 SQL 예약어

### Repository
| 파일 | 내용 |
|------|------|
| `repository/OrderRepository.java` | `Optional<Order> findByOrderNumber(String)` |
| `repository/PaymentRepository.java` | `long countByOrderId(Long)` |

### Client
| 파일 | 내용 |
|------|------|
| `client/PgApproveRequest.java` | `record PgApproveRequest(String orderId, Long amount)` |
| `client/PgApproveResponse.java` | `record PgApproveResponse(String paymentKey, String orderId, String status, Long totalAmount)` |
| `client/PgPaymentException.java` | `RuntimeException` 상속 |
| `client/PgClient.java` | `@Component`, `RestTemplate` 주입, `@Value("${pg.base-url}")` |

### Config
| 파일 | 내용 |
|------|------|
| `config/RestTemplateConfig.java` | connectTimeout 5s, readTimeout **30s** (10초 지연 관찰 가능하도록) |

### Service
| 파일 | 내용 |
|------|------|
| `service/PaymentResult.java` | `record PaymentResult(String orderNumber, String status, String paymentKey)` |
| `service/PaymentService.java` | **핵심 나이브 구현** — 아래 참고 |

```java
// ⚠️ 의도적으로 잘못된 구현 — 두 가지 참사의 원인
@Transactional
public PaymentResult processPayment(String orderNumber) {
    Order order = orderRepository.findByOrderNumber(orderNumber)
        .orElseThrow(...);

    // Bug 1: 트랜잭션 안에서 외부 HTTP 호출 → 커넥션 점유
    PgApproveResponse pgResponse = pgClient.approve(order.getOrderNumber(), order.getAmount());

    // Bug 2: 여기서 예외 발생 시 위 PG 승인은 롤백 불가 → 돈만 빠짐
    Payment payment = new Payment(order.getId(), order.getAmount());
    payment.setPgPaymentKey(pgResponse.paymentKey());
    payment.setStatus(PaymentStatus.SUCCESS);
    paymentRepository.save(payment);

    order.setStatus(OrderStatus.SUCCESS);
    return new PaymentResult(order.getOrderNumber(), "SUCCESS", pgResponse.paymentKey());
}
```

### Controller
| 파일 | 내용 |
|------|------|
| `controller/PaymentRequest.java` | `record PaymentRequest(String orderNumber)` |
| `controller/PaymentController.java` | `POST /api/payments`, Exception catch → 500 FAILED |
| `controller/test/TestResetController.java` | `@Profile("!prod")`, `POST /api/test/reset` — deleteAll + 시드 재생성 |

---

## 검증 방법

### 자동 테스트 실행
```bash
cd payment
./gradlew test
```

예상 결과:
- `ConnectionPoolExhaustionTest` — PASS (`failCount > 0`)
- `DataInconsistencyWithSpyTest` — PASS (PG 호출 확인 + DB 기록 0건)
- `DataInconsistencyWithFkTest` — PASS (FK 예외 발생 + DB 기록 0건)

### 수동 부하 테스트 (커넥션 풀 고갈 시각화)
```bash
# 1. 인프라 + 모니터링 시작
docker compose -f docker-compose.app.yml up -d
docker compose -f docker-compose.monitoring.yml up -d

# 2. WireMock slow 모드 활성화 (pg-approve-success.json 제거 or 파일 교체)

# 3. Spring Boot 실행
./gradlew bootRun

# 4. k6 부하 테스트
docker compose -f docker-compose.monitoring.yml --profile k6 run --rm \
  k6 run --out experimental-prometheus-rw /scripts/phase1-slow-pg.js
```

Grafana에서 확인 (`localhost:3000`):
- `hikaricp_connections_active` → 최대치(10) 도달
- `hikaricp_connections_pending` → 급등
- HTTP 5xx 에러율 급등

### 정합성 검증 쿼리
```sql
-- 주문은 READY인데 결제 기록이 있는 비정상 상태 (PG 성공 → DB 롤백)
SELECT o.order_number, o.status AS order_status, p.status AS payment_status
FROM orders o
LEFT JOIN payment p ON o.id = p.order_id
WHERE o.status = 'READY' AND p.id IS NOT NULL;

-- 상태별 건수
SELECT status, COUNT(*) FROM orders GROUP BY status;
SELECT status, COUNT(*) FROM payment GROUP BY status;
```
