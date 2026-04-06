# Phase 2 진행 현황 — TDD 트랜잭션 분리

## 완료된 Cycle

| Cycle | 내용 | 테스트 파일 | 상태 |
|-------|------|------------|------|
| Cycle 1 | `OrderStatus.PENDING`, `PaymentStatus.PENDING/UNKNOWN` enum 추가 | `domain/OrderStatusTest`, `domain/PaymentStatusTest` | ✅ Green |
| Cycle 2 (도메인) | `Order` 상태 전환 메서드 (`pending/success/fail`) | `domain/OrderTest` | ✅ Green |
| Cycle 3 (도메인) | `Payment` 상태 전환 메서드 (`success/fail/unknown`) | `domain/PaymentTest` | ✅ Green |
| Cycle 4 (계획서 Cycle 2) | `PgClient.queryPayment()` + `PgQueryResponse` 신규 | `client/PgClientQueryTest` | ✅ Green |
| Cycle 5 (계획서 Cycle 3) | `PaymentInternalService.preparePayment()` TX1 + `PaymentRepository` 확장 + 시드 데이터 보충 | `service/PaymentInternalServiceTx1Test` | ✅ Green |
| Cycle 6 (계획서 Cycle 4) | `PaymentInternalService.recordSuccess/Failure/Unknown()` TX2 | `service/PaymentInternalServiceTx2Test` | ✅ Green |

---

## 작업 중 (미완료)

### Cycle 7 (계획서 Cycle 5) — `PaymentService` 오케스트레이션 전면 리팩터

- 테스트 파일 작성 완료: `service/PaymentServiceOrchestrationTest`, `service/PaymentStatusQueryTest`
- 프로덕션 코드 작성 완료: `PaymentService` 전면 리팩터, `PaymentStatusResponse` 신규
- **테스트 정상동작 확인 완료** — 작업 중단 시점

**재개 시 첫 번째 할 일:**
```bash
./gradlew test --tests "*.service.PaymentServiceOrchestrationTest" --tests "*.service.PaymentStatusQueryTest"
```

---

## 남은 Cycle

| Cycle | 내용 | 비고 |
|-------|------|------|
| Cycle 8 (계획서 Cycle 6) | `PaymentController` 확장 — UNKNOWN → HTTP 202, `GET /{orderNumber}/status` 추가 | `controller/PaymentControllerPhase2Test` 신규 |
| Cycle 9 (계획서 Cycle 7) | `UnknownPaymentRecoveryScheduler` 신규 — 1분 주기 UNKNOWN 복구 | `scheduler/UnknownPaymentRecoverySchedulerTest` 신규, `SchedulingConfig` 신규 |

---

## 신규/수정된 파일 목록

| 파일 | 변경 유형 | Cycle |
|------|----------|-------|
| `domain/OrderStatus.java` | 수정 — `PENDING` 추가 | 1 |
| `domain/PaymentStatus.java` | 수정 — `PENDING`, `UNKNOWN` 추가 | 1 |
| `domain/Order.java` | 수정 — `@Setter` 제거, `pending/success/fail()` 추가, 생성자 추가 | 2 |
| `domain/Payment.java` | 수정 — `@Setter` 제거, `success/fail/unknown()` 추가, 생성자 추가 | 3 |
| `client/PgQueryResponse.java` | **신규** | 4 |
| `client/PgClient.java` | 수정 — `queryPayment()` 추가 | 4 |
| `repository/PaymentRepository.java` | 수정 — `findByStatus()`, `findByOrderIdOrderByIdDesc()` 추가 | 5 |
| `test/resources/init/01_schema.sql` | 수정 — 시드 데이터 보충 (ORD-002, ORD-PENDING, ORD-SUCCESS, ORD-UNKNOWN, ORD-PENDING-2) | 5 |
| `service/PaymentInternalService.java` | **신규** — TX1 `preparePayment()`, TX2 3개 메서드 | 5~6 |
| `service/PaymentStatusResponse.java` | **신규** | 7 |
| `service/PaymentService.java` | 전면 수정 — TX 분리 오케스트레이션, `getPaymentStatus()` 추가 | 7 |
| `TestcontainersConfiguration.java` | 수정 — `package-private` → `public` (하위 패키지 테스트 접근) | 4 |

---

## 주요 설계 결정 사항

**`PaymentInternalService` 별도 빈 분리 이유:**
`PaymentService` 내부에서 `this.method()`로 호출하면 Spring AOP 프록시를 우회해 `@Transactional`이 무시됨 (self-invocation 함정). 별도 빈으로 분리해야 TX1/TX2가 독립된 트랜잭션으로 실행됨.

**`recordUnknown()`에서 Order를 PENDING 유지하는 이유:**
타임아웃 시 PG에서 실제로 승인이 완료됐을 수 있음. FAILED로 확정하면 실제로 결제된 건을 실패로 기록하는 오류가 발생. 복구 스케줄러가 PG 조회 API로 최종 상태를 확정할 때까지 PENDING 유지.
