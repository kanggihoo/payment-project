# Phase 3: 멱등성(Idempotency) 보장 구현 계획

## Goal
Phase 2에서 TX를 분리했지만 해결 못한 **중복 결제 문제**를  
Redis SET NX 기반 `Idempotency-Key`로 방어한다.  
`PaymentService.processPayment(orderNumber)` → `processPayment(orderNumber, idempotencyKey)` 시그니처 변경 포함.

---

## TDD 협업 규칙 (매 사이클 준수)

| 단계 | 행위자 | 내용 |
|------|--------|------|
| 1 | AI | 아직 없는 클래스/메서드를 사용하는 **테스트 코드** 작성 |
| 2 | 사람 | `./gradlew compileTestJava` 실행 → 에러 목록 AI에게 전달 |
| 3 | AI | **프로덕션 코드 껍데기** 작성 (컴파일만 통과) |
| 4 | 사람 | `./gradlew test --tests "해당테스트"` 실행 → 런타임 실패(Red) 확인 |
| 5 | AI | **본문 구현** (Green) |
| 6 | 사람 | 테스트 실행 → Green 확인 후 다음 사이클 승인 |

> **한 사이클이 Green이 될 때까지 다음 사이클로 절대 진행하지 않는다.**

---

## 사전 작업 (TDD 아님 — 인프라 변경)

### Step 0-A: build.gradle — Redis 의존성 추가

변경 파일: `payment/build.gradle`

```gradle
// 추가할 의존성
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

- `testImplementation 'org.springframework.boot:spring-boot-starter-session-data-redis-test'`는 이미 있음 → 확인 후 중복 추가 금지
- `// implementation 'org.springframework.boot:spring-boot-starter-session-data-redis'` 주석 제거 대신 **새 라인** 추가 (`data-redis`와 `session-data-redis`는 다른 모듈)

완료 확인: `./gradlew dependencies | grep redis` 에서 `spring-data-redis` 보임

### Step 0-B: application.yml — Redis 설정 추가

변경 파일: `payment/src/main/resources/application.yml`

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

완료 확인: 앱 구동 시 Redis 연결 시도 로그 확인 (연결 실패해도 Bean 등록 자체는 성공)

### Step 0-C: docker-compose 확인

`docker-compose.app.yml`에 Redis 서비스가 없으면 추가:

```yaml
redis:
  image: redis:7.2-alpine
  ports:
    - "6379:6379"
```

완료 확인: `docker compose -f docker-compose.app.yml up redis -d` 정상 기동

---

## TDD 사이클 목록

### Cycle 1 — DuplicatePaymentException

**목적**: 멱등성 키 중복 감지 시 던질 예외 클래스  
**파일**: `exception/DuplicatePaymentException.java`

#### Red 유도 (AI가 작성할 테스트)
```
존재하지 않는 DuplicatePaymentException 클래스를 import 하는 테스트
→ compileTestJava 실패
```

#### Green 구현 목표
- `RuntimeException` 상속
- 생성자: `DuplicatePaymentException(String message)`
- 클래스 주석: Phase 3에서 왜 이 예외가 필요한지

---

### Cycle 2 — RedisIdempotencyStore (단위 테스트)

**목적**: Redis SET NX 기반 멱등성 키 저장소  
**파일**: `idempotency/RedisIdempotencyStore.java`  
**테스트**: `idempotency/RedisIdempotencyStoreTest.java` (Testcontainers Redis)

#### Red 유도 (AI가 작성할 테스트)
```java
// 존재하지 않는 RedisIdempotencyStore를 주입받는 테스트
@SpringBootTest
@Testcontainers
class RedisIdempotencyStoreTest {
    @Container static GenericContainer<?> redis = ...;

    @Autowired RedisIdempotencyStore store; // 아직 없음 → 컴파일 에러

    @Test void tryAcquire_첫번째_요청은_true를_반환한다() { ... }
    @Test void tryAcquire_두번째_요청은_false를_반환한다() { ... }
    @Test void markCompleted_후_getExistingResponse_값을_반환한다() { ... }
    @Test void release_후_다시_tryAcquire_가능하다() { ... }
}
```

#### Green 구현 목표
- `tryAcquire(String key)` → `Boolean` (SET NX 원자적 연산)
- `markCompleted(String key, String responseJson)` → void
- `getExistingResponse(String key)` → `Optional<String>`
- `release(String key)` → void
- TTL: 24시간
- KEY PREFIX: `"idempotency:"`
- 메서드별 주석: 각 메서드가 Phase 3 어느 시나리오를 처리하는지

---

### Cycle 3 — PaymentService 멱등성 통합 (통합 테스트)

**목적**: `processPayment`에 멱등성 키 파라미터 추가 및 중복 차단 로직  
**파일**: `service/PaymentService.java` 수정  
**테스트**: `service/PaymentServiceIdempotencyTest.java` (WireMock + Testcontainers)

#### Red 유도 (AI가 작성할 테스트)
```java
// processPayment(String, String) 시그니처가 없어 컴파일 에러
class PaymentServiceIdempotencyTest {
    @Test void 동일_멱등성키_재요청시_PG를_재호출하지_않는다() {
        service.processPayment("ORD-001", "idem-key-1"); // 2-arg 없음 → 에러
        service.processPayment("ORD-001", "idem-key-1");
        // PG 호출 1회만 확인
    }

    @Test void 멱등성키_없는_기존_호출도_동작해야한다() { ... } // 하위호환 확인용
}
```

#### Green 구현 목표
- 기존 `processPayment(orderNumber)` → `processPayment(orderNumber, idempotencyKey)`
- `PaymentController`도 함께 수정 (컴파일 에러 연쇄 방지)
- 흐름:
  1. `getExistingResponse` → 있으면 캐싱 결과 반환 (PG 미호출)
  2. `tryAcquire` → false면 `DuplicatePaymentException`
  3. Phase 2 TX1→PG→TX2 로직 실행
  4. `markCompleted` (성공 시)
  5. `release` (실패 시 → 재시도 허용)
- 각 단계 주석: 왜 이 순서인지, Phase 3에서 어느 시나리오를 막는지

---

### Cycle 4 — PaymentController Idempotency-Key 헤더 처리

**목적**: `POST /api/payments` 에 `Idempotency-Key` 헤더 추가  
**파일**: `controller/PaymentController.java` 수정  
**테스트**: `controller/PaymentControllerIdempotencyTest.java`

#### Red 유도 (AI가 작성할 테스트)
```java
// @RequestHeader("Idempotency-Key") 없는 요청 → 400 기대하는 테스트
// DuplicatePaymentException → 409 반환 기대하는 테스트
class PaymentControllerIdempotencyTest {
    @Test void 멱등성키_헤더_없으면_400_반환() { ... }
    @Test void 중복_요청이면_409_반환() { ... } // service stub이 DuplicatePaymentException
    @Test void 정상_요청이면_200_반환() { ... }
}
```

#### Green 구현 목표
- `@RequestHeader("Idempotency-Key") String idempotencyKey` 파라미터 추가
- `DuplicatePaymentException` catch → HTTP 409 CONFLICT 반환
- `@RequestHeader` 누락 시 Spring이 자동으로 400 반환 (별도 처리 불필요)
- 주석: 각 HTTP 상태 코드를 반환하는 이유

---

### Cycle 5 — 동시성 통합 테스트 (100건 중복 → 1건만 통과)

**목적**: Cycle 2~4가 실제로 동시 요청을 막는지 검증  
**파일**: `controller/IdempotencyConcurrencyTest.java`

#### Red 유도 (AI가 작성할 테스트)
```java
// 100 스레드가 동일 Idempotency-Key로 동시 POST
// 기대: successCount == 1, duplicateCount == 99
// WireMock PG 호출 횟수 == 1
class IdempotencyConcurrencyTest {
    // 아직 IdempotencyConcurrencyTest 클래스가 없음
    // → 컴파일 에러 아님, 하지만 테스트 실패 유도 목적
}
```

> 이 사이클은 **컴파일 에러 없이 런타임 실패**를 목표로 한다.  
> Cycle 3~4 Green 이후 실제 동시성 경쟁 조건에서 멱등성이 보장되는지 확인.

#### Green 확인 목표
- `successCount == 1` (PG 호출 정확히 1건)
- `duplicateCount == 99` (DuplicatePaymentException → 409)
- `wireMock.verify(1, postRequestedFor(...))` 통과

---

### Cycle 6 — 재시도 캐싱 테스트 (순차 재요청 → 캐싱 결과 반환)

**목적**: 성공 후 동일 키로 재시도 시 PG 미호출, 동일 결과 반환 검증  
**파일**: `controller/IdempotencyRetryTest.java` 또는 Cycle 5 파일에 추가

#### Green 확인 목표
- 1차 요청: HTTP 200, paymentKey 반환
- 2차 요청 (동일 idempotencyKey): HTTP 200, **동일** paymentKey 반환
- WireMock PG 호출 횟수: 1 (캐싱 동작 확인)

---

## 파일 변경 요약

| 파일 | 변경 유형 | 관련 사이클 |
|------|-----------|-------------|
| `build.gradle` | 수정 (Redis 의존성) | Step 0-A |
| `application.yml` | 수정 (Redis 설정) | Step 0-B |
| `docker-compose.app.yml` | 수정 (Redis 서비스) | Step 0-C |
| `exception/DuplicatePaymentException.java` | **신규** | Cycle 1 |
| `idempotency/RedisIdempotencyStore.java` | **신규** | Cycle 2 |
| `idempotency/RedisIdempotencyStoreTest.java` | **신규** (테스트) | Cycle 2 |
| `service/PaymentService.java` | 수정 (시그니처 변경) | Cycle 3 |
| `service/PaymentServiceIdempotencyTest.java` | **신규** (테스트) | Cycle 3 |
| `controller/PaymentController.java` | 수정 (헤더 추가, 409 처리) | Cycle 4 |
| `controller/PaymentControllerIdempotencyTest.java` | **신규** (테스트) | Cycle 4 |
| `controller/IdempotencyConcurrencyTest.java` | **신규** (테스트) | Cycle 5 |

---

## Done When

- [ ] `./gradlew test` 전체 Green (Phase 2 테스트 포함 회귀 없음)
- [ ] 동일 `Idempotency-Key` 100건 동시 요청 → PG 호출 1건, 차단 99건 검증
- [ ] 재시도 시 캐싱된 결과 반환, PG 미호출 검증
- [ ] 모든 신규 코드에 Phase 3 목적 주석 포함

---

## 진행 순서 (의존성 그래프)

```
Step 0-A,B,C (병렬 가능)
     ↓
Cycle 1: DuplicatePaymentException
     ↓
Cycle 2: RedisIdempotencyStore
     ↓
Cycle 3: PaymentService (+ PaymentController 연동 수정)
     ↓
Cycle 4: PaymentController 단위 테스트
     ↓
Cycle 5: 동시성 통합 테스트
     ↓
Cycle 6: 재시도 캐싱 통합 테스트
```