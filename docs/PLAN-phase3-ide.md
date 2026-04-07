# Phase 3 구현 계획: 멱등성(Idempotency) + Redis

## Context

Phase 2에서 TX 분리로 데이터 정합성은 해결했지만, **중복 결제** 문제는 미해결 상태다.
같은 요청이 두 번 오면 (타임아웃 재시도 / 따닥 클릭) PG가 두 번 승인한다.

Phase 3는 `Idempotency-Key` 헤더 + Redis SET NX로 중복 요청을 차단한다.

---

## 핵심 규칙

- TDD: Red(테스트 먼저) → Skeleton(컴파일 통과) → Green(구현 완성)
- **한 사이클씩만 진행. 사람의 실행 결과 없이 다음으로 절대 진행 금지**
- 모든 코드에 Phase 3가 해결하는 문제를 설명하는 주석 필수
- Spring Boot 4.0.5 기준: `@MockitoBean`, `MockMvcTester`, `RestTestClient`, Testcontainers 2.0

---

## Cycle 0: 인프라 변경 (TDD 없음 — 순수 설정)

**대상 파일:**
- [payment/build.gradle](payment/build.gradle)
- [payment/src/main/resources/application.yml](payment/src/main/resources/application.yml)
- [docker-compose.app.yml](docker-compose.app.yml)

**변경 내용:**

### build.gradle
```groovy
// 주석 해제 및 수정 (session 변형 → data-redis)
implementation 'org.springframework.boot:spring-boot-starter-data-redis'

// 테스트용 Redis Testcontainer (GenericContainer 방식)
// testcontainers-redis 별도 아티팩트 없이 표준 GenericContainer 사용
// spring-boot-starter-session-data-redis-test 는 이미 존재함
```

### application.yml
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### docker-compose.app.yml
```yaml
redis:
  image: redis:7.2-alpine
  container_name: payment-redis
  ports:
    - "6379:6379"
```

---

## Cycle 1: DuplicatePaymentException + Controller 409 응답

**목표:** 중복 요청 시 409 Conflict를 반환하는 컨트롤러 동작 정의

### Step 1 — Red (테스트 작성)

**파일:** `src/test/java/com/example/payment/controller/PaymentControllerPhase3Test.java`

테스트 내용:
- `@WebMvcTest(PaymentController.class)`
- `@MockitoBean PaymentService` 에서 `DuplicatePaymentException` throw → HTTP 409 응답 검증
- `Idempotency-Key` 헤더 없을 때 400 Bad Request 검증
- `MockMvcTester` (Spring Boot 4 스타일) 사용

**예상 컴파일 에러:**
```
error: cannot find symbol — DuplicatePaymentException
error: cannot find symbol — @RequestHeader("Idempotency-Key")
```

### Step 2 — Skeleton (컴파일 통과용 껍데기)

**새 파일:** `src/main/java/com/example/payment/exception/DuplicatePaymentException.java`
- `RuntimeException` 상속, 생성자만 구현

**수정:** `PaymentController.java`
- `pay()` 메서드에 `@RequestHeader("Idempotency-Key") String idempotencyKey` 파라미터 추가
- `DuplicatePaymentException` catch → 409 반환 (아직 서비스 연동 없음)

**수정:** `PaymentService.processPayment()`
- `idempotencyKey` 파라미터 추가 (구현 없이 서명만)

**예상 런타임 실패:** 서비스 mock이 예외를 던지지 않아 409 미반환

### Step 3 — Green (구현 완성)

컨트롤러는 `DuplicatePaymentException` → 409 반환. 서비스 mock 설정으로 Green 확인.

---

## Cycle 2: RedisIdempotencyStore 단위 테스트

**목표:** Redis SET NX 기반 멱등성 저장소 구현

### Step 1 — Red

**파일:** `src/test/java/com/example/payment/idempotency/RedisIdempotencyStoreTest.java`

테스트 내용:
- `@ExtendWith(MockitoExtension.class)` (순수 단위 테스트, Spring 컨텍스트 불필요)
- `@Mock StringRedisTemplate` 주입
- `tryAcquire()` — SET NX 성공 시 true, 이미 존재 시 false
- `markCompleted()` — SET with TTL 호출 검증
- `getExistingResponse()` — "PROCESSING" 값은 empty, JSON 값은 반환
- `release()` — DELETE 호출 검증

**예상 컴파일 에러:**
```
error: cannot find symbol — RedisIdempotencyStore
```

### Step 2 — Skeleton

**새 파일:** `src/main/java/com/example/payment/idempotency/RedisIdempotencyStore.java`
- `@Component`, `@RequiredArgsConstructor`
- `StringRedisTemplate` 주입
- 메서드 4개 시그니처만 (`return false/null/Optional.empty()`)

**예상 런타임 실패:** tryAcquire가 항상 false 반환

### Step 3 — Green

```java
// KEY_PREFIX = "idempotency:", TTL = 24시간
boolean tryAcquire(String key)       // setIfAbsent(...)
void markCompleted(String key, String json) // set(..., TTL)
Optional<String> getExistingResponse(String key)  // GET, PROCESSING이면 empty
void release(String key)             // delete(...)
```

---

## Cycle 3: PaymentService 멱등성 통합 — 중복 차단

**목표:** 같은 키로 두 번째 요청 시 DuplicatePaymentException throw

### Step 1 — Red

**파일:** `src/test/java/com/example/payment/service/PaymentServiceIdempotencyTest.java`

테스트 내용:
- `@SpringBootTest` + PostgreSQL Testcontainer + Redis Testcontainer
- Redis Testcontainer 설정:
  ```java
  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry r) {
      r.add("spring.data.redis.host", redis::getHost);
      r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }
  ```
- WireMock: `@EnableWireMock` + `@DynamicPropertySource`로 pg.base-url 설정
- `processPayment("ORD-001", "idem-key-001")` 두 번 호출 → 두 번째에 `DuplicatePaymentException` 검증

**예상 런타임 실패:** 서비스가 아직 idempotencyKey 파라미터를 무시함

### Step 2 — Skeleton

**수정:** `PaymentService.processPayment(String orderNumber, String idempotencyKey)`
- `RedisIdempotencyStore` 주입 (`@RequiredArgsConstructor`)
- 메서드 초반에 `tryAcquire` 호출만 추가 (예외 throw 없이)

### Step 3 — Green

```java
// 1. 이미 완료된 요청인지 확인
Optional<String> existing = idempotencyStore.getExistingResponse(idempotencyKey);
if (existing.isPresent()) { return deserialize(existing.get()); }

// 2. SET NX로 처음 처리하는 요청인지 확인
boolean acquired = idempotencyStore.tryAcquire(idempotencyKey);
if (!acquired) {
    throw new DuplicatePaymentException("이미 처리 중인 결제: " + idempotencyKey);
}

try {
    // 3. 기존 Phase 2 로직
    // ...
    idempotencyStore.markCompleted(idempotencyKey, serialize(result));
    return result;
} catch (Exception e) {
    idempotencyStore.release(idempotencyKey);  // 실패 시 키 해제 → 재시도 허용
    throw e;
}
```

---

## Cycle 4: 재시도 시 캐싱된 결과 반환 검증

**목표:** 성공 후 동일 키 재요청 시 PG 재호출 없이 캐싱 결과 반환

### Step 1 — Red

**파일:** `src/test/java/com/example/payment/service/PaymentServiceRetryTest.java`

테스트 내용:
- Cycle 3와 동일 인프라 설정
- 1차 요청 성공 → 2차 동일 키 요청 → 200 OK + 동일 paymentKey 반환
- WireMock `verify(1, ...)` → PG 호출 1번만 확인

**예상 런타임 실패:** 2차 요청도 `DuplicatePaymentException` throw (PROCESSING 상태로 남음)

### Step 3 — Green

Cycle 3의 `getExistingResponse` 분기가 올바른 JSON을 반환하도록
`markCompleted` 호출 후 Redis에 실제 JSON이 저장됨을 확인.

`ObjectMapper` 직렬화/역직렬화:
```java
// PaymentService에 ObjectMapper @Autowired 추가
private String serialize(PaymentResult r) { return objectMapper.writeValueAsString(r); }
private PaymentResult deserialize(String json) { return objectMapper.readValue(json, PaymentResult.class); }
```

---

## Cycle 5: 동시성 테스트 — 100건 동시 요청 → 1건만 PG 도달

**목표:** Redis SET NX의 원자성으로 동시 중복을 방어함을 검증

### Step 1 — Red

**파일:** `src/test/java/com/example/payment/controller/IdempotencyConcurrencyTest.java`

테스트 내용:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `RestTestClient` (Spring Boot 4 — TestRestTemplate 대신)
- 100개 스레드, 동일 idempotency key, 동일 orderNumber
- `AtomicInteger successCount`, `duplicateCount`
- `assertThat(successCount.get()).isEqualTo(1)`
- `assertThat(duplicateCount.get()).isEqualTo(99)`
- `wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")))`

**예상 런타임 실패:** Redis 연동 문제 or 레이스 컨디션 발생 시

### Step 3 — Green

Cycle 3 구현이 올바르면 통과. 실패 시 Redis SET NX 원자성 확인.

---

## Cycle 6 (선택): TossPayments Sandbox 클라이언트

**목표:** WireMock → TossPayments Sandbox 전환 (E2E 수동 검증용)

**새 파일:** `src/main/java/com/example/payment/client/TossPaymentsClient.java`
- `PgClient` 인터페이스 구현
- Basic Auth: `secretKey + ":"` Base64 인코딩
- `/v1/payments/confirm` POST, `/v1/payments/orders/{orderNumber}` GET

**application.yml (test profile)**에서만 TossPayments URL 사용.
통합 테스트는 여전히 WireMock 사용.

---

## 수정 대상 파일 전체 목록

| 파일 | 변경 유형 |
|------|---------|
| [payment/build.gradle](payment/build.gradle) | Redis 의존성 추가 |
| [payment/src/main/resources/application.yml](payment/src/main/resources/application.yml) | Redis 설정 추가 |
| [docker-compose.app.yml](docker-compose.app.yml) | Redis 서비스 추가 |
| [payment/src/main/java/.../controller/PaymentController.java](payment/src/main/java/com/example/payment/controller/PaymentController.java) | Idempotency-Key 헤더 추가, 409 처리 |
| [payment/src/main/java/.../service/PaymentService.java](payment/src/main/java/com/example/payment/service/PaymentService.java) | idempotencyKey 파라미터, RedisIdempotencyStore 연동 |

**신규 파일:**

| 파일 | 역할 |
|------|------|
| `.../exception/DuplicatePaymentException.java` | 중복 결제 예외 |
| `.../idempotency/RedisIdempotencyStore.java` | Redis SET NX 멱등성 저장소 |
| `...test.../controller/PaymentControllerPhase3Test.java` | Cycle 1 테스트 |
| `...test.../idempotency/RedisIdempotencyStoreTest.java` | Cycle 2 테스트 |
| `...test.../service/PaymentServiceIdempotencyTest.java` | Cycle 3 테스트 |
| `...test.../service/PaymentServiceRetryTest.java` | Cycle 4 테스트 |
| `...test.../controller/IdempotencyConcurrencyTest.java` | Cycle 5 테스트 |

---

## 검증 방법

각 사이클 완료 시:
1. `./gradlew compileTestJava` → 컴파일 에러 없음 확인
2. `./gradlew test --tests "해당 테스트 클래스"` → Green 확인
3. Cycle 5 완료 후: `./gradlew test` 전체 테스트 회귀 없음 확인
4. 수동 E2E: docker-compose up → k6 scripts/phase3-idempotency.js 실행
