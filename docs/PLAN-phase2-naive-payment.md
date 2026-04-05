# Phase 2: 트랜잭션 분리 + Unknown State 핸들링 — TDD 구현 계획

## Context

Phase 1에서 `@Transactional` 안에 PG 외부 호출을 넣어 두 가지 참사를 증명했다:

1. **커넥션 풀 고갈** — DB 커넥션을 쥔 채 PG 응답 10초 대기 → 풀(5개) 3초 만에 고갈
2. **데이터 불일치** — PG 승인 성공 후 DB 저장 실패 시 롤백 → "돈은 빠졌는데 기록 없음"

Phase 2는 트랜잭션을 3단계로 분리하여 두 문제를 해결하고, 그 과정에서 드러나는 **Unknown State** 문제(타임아웃 시 결제 성공 여부 불명)를 상태 머신 + 복구 스케줄러로 처리한다.

```
TX1: Order → PENDING 사전 커밋 → DB 커넥션 반납
(트랜잭션 없음): PG 승인 요청
TX2: 결과에 따라 SUCCESS / FAILED / UNKNOWN 기록
[스케줄러]: 1분마다 UNKNOWN → PG 조회 API → 최종 상태 확정
```

**TDD 협업 규칙:**

- AI는 아직 존재하지 않는 클래스/메서드를 사용하는 테스트를 먼저 작성 (컴파일 에러 = Red 유도)
- 사람이 `./gradlew compileTestJava` 실행 → 에러 목록 AI에게 전달
- AI가 프로덕션 코드 껍데기 작성 → 사람이 실행 → Red(런타임 실패) 확인
- AI가 본문 구현 → 사람이 실행 → Green 확인
- **한 번에 하나의 사이클만 진행, 사람의 실행 결과 없이 다음 사이클로 넘어가지 않음**
- **모든 코드에 주석 필수** (왜 이렇게 했는지, Phase 2에서 무엇을 해결하는지 명시)

---

## 현재 상태 (Phase 1 코드)

**수정 대상 파일:**

- [OrderStatus.java](payment/src/main/java/com/example/payment/domain/OrderStatus.java) — `READY, SUCCESS, FAILED` (PENDING 없음)
- [PaymentStatus.java](payment/src/main/java/com/example/payment/domain/PaymentStatus.java) — `READY, SUCCESS, FAILED` (PENDING, UNKNOWN 없음)
- [PgClient.java](payment/src/main/java/com/example/payment/client/PgClient.java) — `approve()` 메서드만 있음, `queryPayment()` 없음
- [PaymentRepository.java](payment/src/main/java/com/example/payment/repository/PaymentRepository.java) — `countByOrderId()` 만 있음
- [PaymentService.java](payment/src/main/java/com/example/payment/service/PaymentService.java) — `@Transactional` 하나로 PG 호출까지 전부 처리 (문제 코드)
- [PaymentController.java](payment/src/main/java/com/example/payment/controller/PaymentController.java) — UNKNOWN 분기 없음, 상태 조회 API 없음
- [init/01_schema.sql](payment/src/test/resources/init/01_schema.sql) — 시드 데이터 `ORD-001` 하나뿐

**신규 생성 파일:**

- `client/PgQueryResponse.java`
- `service/PaymentInternalService.java`
- `service/PaymentStatusResponse.java`
- `scheduler/UnknownPaymentRecoveryScheduler.java`

---

## TDD 사이클 (총 7개)

### Cycle 1 — 도메인 enum 확장

**목적:** `PENDING`, `UNKNOWN` 값이 없으면 이후 모든 사이클이 컴파일 불가.

**테스트 작성 (신규):**

- `domain/OrderStatusTest.java` — `OrderStatus.PENDING` 참조 → `cannot find symbol` 유발
- `domain/PaymentStatusTest.java` — `PaymentStatus.PENDING`, `PaymentStatus.UNKNOWN` 참조 → 컴파일 에러 유발

**Green을 위한 프로덕션 코드:**

- `OrderStatus`: `READY, PENDING, SUCCESS, FAILED`
- `PaymentStatus`: `READY, PENDING, SUCCESS, FAILED, UNKNOWN`

**검증:** `./gradlew test --tests "*.domain.*StatusTest"`

---

### Cycle 2 — PgClient.queryPayment + PgQueryResponse

**목적:** UNKNOWN 복구 스케줄러(Cycle 7)가 PG 조회 API를 필요로 함.

**테스트 작성 (신규):**

- `client/PgClientQueryTest.java` — `PgQueryResponse` 클래스 참조, `pgClient.queryPayment(String)` 호출 → 컴파일 에러 유발
- 시나리오: 조회 성공(200→PgQueryResponse 반환), 조회 실패(404→PgPaymentException)

**Green을 위한 프로덕션 코드:**

- `PgQueryResponse.java` 신규: `record(paymentKey, orderId, status, totalAmount)`
- `PgClient.java` 수정: `queryPayment(String paymentKey)` 추가 — `GET /v1/payments/{paymentKey}`

**검증:** `./gradlew test --tests "*.client.PgClientQueryTest"`

---

### Cycle 3 — PaymentInternalService TX1 + Repository 확장 + 시드 데이터 보충

**목적:** TX1 사전 커밋 메서드 검증. Cycle 5, 7을 위한 Repository 메서드와 시드 데이터도 이 사이클에서 준비.

**테스트 작성 (신규):**

- `service/PaymentInternalServiceTx1Test.java` — `PaymentInternalService` 클래스 참조, `preparePayment(Long orderId, Long amount)` 호출 → 컴파일 에러 유발
- 시나리오:
  - 정상 호출 시 Order=PENDING, Payment=PENDING으로 저장 및 paymentId 반환
  - 존재하지 않는 orderId → `IllegalArgumentException`
  - READY 아닌 Order → `IllegalStateException`
- **테스트 격리**: `@Transactional` 어노테이션으로 각 테스트 후 자동 롤백하여 시드 데이터 상태 유지

**이 사이클에서 함께 수정:**

- `PaymentRepository.java`: 아래 2개 메서드 추가
  - `List<Payment> findByStatus(PaymentStatus status)` — Cycle 7 선행 준비 (UNKNOWN 결제 조회)
  - `List<Payment> findByOrderIdOrderByIdDesc(Long orderId)` — Cycle 5 `getPaymentStatus()` 에서 사용 (전체 조회 후 필터 대신 orderId로 직접 조회)
- `init/01_schema.sql`: 시드 데이터 보충
  - **주의:** 컬럼명 반드시 명시. `id`가 BIGSERIAL(첫 번째 컬럼)이므로 컬럼명 없이 VALUES만 쓰면 `id`에 문자열이 들어가 타입 오류 발생
  ```sql
  INSERT INTO orders (order_number, amount, status) VALUES ('ORD-002', 5000, 'READY');
  INSERT INTO orders (order_number, amount, status) VALUES ('ORD-PENDING', 2000, 'PENDING');
  INSERT INTO orders (order_number, amount, status) VALUES ('ORD-SUCCESS', 3000, 'SUCCESS');
  INSERT INTO orders (order_number, amount, status) VALUES ('ORD-UNKNOWN', 4000, 'PENDING');
  -- UNKNOWN 상태 결제 레코드 (복구 스케줄러 테스트용)
  INSERT INTO payment (order_id, pg_payment_key, amount, status)
    SELECT id, 'PG-KEY-UNKNOWN-001', 4000, 'UNKNOWN' FROM orders WHERE order_number = 'ORD-UNKNOWN';
  ```

**Green을 위한 프로덕션 코드:**

- `PaymentInternalService.java` 신규: `@Transactional preparePayment(Long orderId, Long amount)` 구현
  - Order 조회 → READY 검증 → PENDING으로 변경 → Payment(PENDING) INSERT → paymentId 반환
  - **왜 별도 빈으로 분리?** `PaymentService` 내부에서 `this.method()` 호출 시 Spring AOP 프록시가 우회되어 `@Transactional`이 무시됨 (self-invocation 함정)

**검증:** `./gradlew test --tests "*.service.PaymentInternalServiceTx1Test"`

---

### Cycle 4 — PaymentInternalService TX2 (3가지 결과 경로)

**목적:** PG 호출 결과에 따른 상태 기록 메서드 3개 검증.

**테스트 작성 (신규):**

- `service/PaymentInternalServiceTx2Test.java` — `recordSuccess()`, `recordFailure()`, `recordUnknown()` 호출 → 컴파일 에러 유발
- **테스트 격리**: 각 테스트 메서드가 `@Transactional`로 독립된 데이터를 사용하도록 롤백 보장. 테스트별로 PENDING 상태 Payment를 직접 생성하여 공유 시드 데이터 충돌 방지
- 시나리오 3개:
  1. `recordSuccess(paymentId, pgKey)` → Payment=SUCCESS, Order=SUCCESS, pgPaymentKey 저장
  2. `recordFailure(paymentId)` → Payment=FAILED, Order=FAILED
  3. `recordUnknown(paymentId)` → Payment=UNKNOWN, Order는 PENDING **유지** (변경 안 함)
     - 이유: 타임아웃 시 PG에서 실제로 승인됐을 수 있으므로 FAILED로 확정하는 것은 위험

**Green을 위한 프로덕션 코드:**

- `PaymentInternalService.java` 수정: 3개 메서드 추가 (각각 독립 `@Transactional`)

**검증:** `./gradlew test --tests "*.service.PaymentInternalServiceTx2Test"`

---

### Cycle 5 — PaymentService 오케스트레이션 전면 리팩터 + PaymentStatusResponse

**목적:** `PaymentService.processPayment()`를 TX 분리 구조로 변경. Phase 1 문제 해결 검증 포함.

**Phase 1 테스트 처리 (이 사이클 시작 전):**

- `ConnectionPoolExhaustionTest`, `DataInconsistencyWithSpyTest`를 `phase1/` 하위 패키지로 이동
- `@Tag("phase1")` 어노테이션 추가 → 평상시 `./gradlew test`에서 제외 가능
- 이유: Phase 2 코드로 변경 후 이 테스트들의 검증 조건이 깨지므로, Phase 1 증거로 보존하되 Phase 2 테스트 실행과 분리

**테스트 작성 (신규):**

- `service/PaymentServiceOrchestrationTest.java` — WireMock으로 PG 응답 제어
  - 시나리오: PG 성공 → SUCCESS / PG 4xx → FAILED / 네트워크 단절(Fault) → UNKNOWN 반환 (예외가 아닌 결과값으로 반환)
- `service/PaymentStatusQueryTest.java` — `paymentService.getPaymentStatus(String)`, `PaymentStatusResponse` 참조 → 컴파일 에러 유발
- `service/ConnectionPoolNotExhaustedPhase2Test.java` — Phase 1 문제 해결 회귀 검증
  - PG 10초 지연 + 커넥션 풀 5개 + 동시 요청 20개 → 실패 건수 **0**이어야 함 (Phase 1은 >0)

**Green을 위한 프로덕션 코드:**

- `PaymentStatusResponse.java` 신규: `record(orderNumber, orderStatus, paymentStatus, paymentKey)`
- `PaymentService.java` 전면 수정:
  - 클래스 레벨 `@Transactional` 제거
  - `processPayment()`: TX1(`preparePayment`) → PG 호출(트랜잭션 밖) → TX2(`recordSuccess/Failure/Unknown`) 오케스트레이션
  - `getPaymentStatus()`: `findByOrderIdOrderByIdDesc(orderId)`로 최신 Payment 조회 (전체 조회 후 스트림 필터 방식 사용 금지)

**검증:** `./gradlew test --tests "*.service.PaymentService*" "*.service.ConnectionPool*Phase2*"`

---

### Cycle 6 — PaymentController 확장

**목적:** UNKNOWN → HTTP 202, `GET /{orderNumber}/status` 엔드포인트 추가.

**테스트 작성 (신규):**

- `controller/PaymentControllerPhase2Test.java` — `@WebMvcTest`
  - POST 성공 → 200 / POST UNKNOWN → **202** / POST FAILED → 200
  - GET `/api/payments/ORD-001/status` → 200 + PaymentStatusResponse
  - GET `/api/payments/NOT-EXIST/status` → 404

**Green을 위한 프로덕션 코드:**

- `PaymentController.java` 수정:
  - `pay()`: status가 `"UNKNOWN"`이면 `ResponseEntity.accepted()` (202), 그 외 200
  - `getStatus(@PathVariable)` 추가: `IllegalArgumentException` → 404

**검증:** `./gradlew test --tests "*.controller.PaymentControllerPhase2Test"`

---

### Cycle 7 — UnknownPaymentRecoveryScheduler

**목적:** 1분 주기로 UNKNOWN 결제를 PG 조회 API로 복구.

**테스트 작성 (신규):**

- `scheduler/UnknownPaymentRecoverySchedulerTest.java` — `UnknownPaymentRecoveryScheduler` 클래스, `scheduler.recover()` 메서드 참조 → 컴파일 에러 유발
- 시나리오 3개:
  1. PG 조회 `DONE` → Payment=SUCCESS, Order=SUCCESS
  2. PG 조회 404 (`PgPaymentException`) → Payment=FAILED, Order=FAILED
  3. PG 조회 네트워크 단절 → Payment=UNKNOWN **유지** (다음 주기에 재시도)

**Green을 위한 프로덕션 코드:**

- `UnknownPaymentRecoveryScheduler.java` 신규:
  - `recover()` 메서드: `findByStatus(UNKNOWN)` 조회 → PG 조회 → `recordSuccess`/`recordFailure` 호출
  - 네트워크 에러 시 catch 후 UNKNOWN 유지 + 로그 경고
  - `@Scheduled(fixedDelay = 60_000)` 어노테이션은 메서드에 부착
- `SchedulingConfig.java` 신규 (별도 설정 클래스로 분리):
  - `@Configuration @EnableScheduling` — `PaymentApplication`에 직접 붙이지 않는 이유: 테스트 컨텍스트 로딩 시 스케줄러가 자동 실행되어 다른 테스트를 간섭할 수 있음
  - 스케줄러 테스트에서는 `recover()`를 수동으로 직접 호출하여 검증

**검증:** `./gradlew test --tests "*.scheduler.UnknownPaymentRecoverySchedulerTest"`

---

## 파일별 변경 요약

| 파일                                             | 변경 유형                             | 사이클    |
| ------------------------------------------------ | ------------------------------------- | --------- |
| `domain/OrderStatus.java`                        | 수정 (PENDING 추가)                   | Cycle 1   |
| `domain/PaymentStatus.java`                      | 수정 (PENDING, UNKNOWN 추가)          | Cycle 1   |
| `client/PgQueryResponse.java`                    | **신규**                              | Cycle 2   |
| `client/PgClient.java`                           | 수정 (queryPayment 추가)              | Cycle 2   |
| `repository/PaymentRepository.java`              | 수정 (findByStatus, findByOrderId 추가) | Cycle 3   |
| `test/resources/init/01_schema.sql`              | 수정 (시드 데이터 보충, 컬럼명 명시)  | Cycle 3   |
| `service/PaymentInternalService.java`            | **신규** (TX1, TX2)                   | Cycle 3~4 |
| `phase1/ConnectionPoolExhaustionTest.java`       | 이동 + @Tag("phase1") 추가            | Cycle 5 전 |
| `phase1/DataInconsistencyWithSpyTest.java`       | 이동 + @Tag("phase1") 추가            | Cycle 5 전 |
| `service/PaymentStatusResponse.java`             | **신규**                              | Cycle 5   |
| `service/PaymentService.java`                    | 전면 수정 (TX 분리)                   | Cycle 5   |
| `controller/PaymentController.java`              | 수정 (202, 상태 조회)                 | Cycle 6   |
| `scheduler/UnknownPaymentRecoveryScheduler.java` | **신규**                              | Cycle 7   |
| `config/SchedulingConfig.java`                   | **신규** (@EnableScheduling 분리)     | Cycle 7   |

---

## 주의 사항

1. **Self-invocation 함정**: `PaymentService`가 자기 자신의 메서드를 `this.`로 호출하면 `@Transactional` 무시됨 → `PaymentInternalService`를 별도 빈으로 분리하는 이유
2. **ddl-auto: validate**: `01_schema.sql` 스키마와 JPA 엔티티가 반드시 일치해야 함. enum은 VARCHAR(50) 컬럼에 저장되므로 enum 값 추가만으로 스키마 변경 불필요
3. **테스트 격리**: 통합 테스트에서 `@Transactional`로 롤백 보장. 단, `@SpringBootTest`의 실제 트랜잭션 커밋을 검증해야 하는 경우(TX1 커밋 후 커넥션 반납 확인)에는 롤백 어노테이션을 제거하고 `@AfterEach`에서 직접 데이터 정리
4. **`@EnableScheduling` 분리**: `PaymentApplication`에 직접 붙이면 테스트 컨텍스트 로딩 시 스케줄러가 자동 실행되어 다른 테스트 간섭 가능 → `SchedulingConfig.java`로 분리
5. **`getPaymentStatus()` 구현**: `paymentRepository.findAll()` 후 스트림 필터 방식 금지. `findByOrderIdOrderByIdDesc(orderId)`로 DB 레벨에서 직접 조회

---

## 최종 검증

```bash
# 전체 테스트 실행 (phase1 태그 제외)
./gradlew test

# Phase 1 문제 증명 테스트 (보존용, 별도 실행)
./gradlew test -Ptags="phase1"

# Phase 2 핵심 검증: 커넥션 풀 고갈 해결
./gradlew test --tests "*.ConnectionPoolNotExhaustedPhase2Test"

# Phase 2 핵심 검증: Unknown State 복구
./gradlew test --tests "*.scheduler.UnknownPaymentRecoverySchedulerTest"
```
