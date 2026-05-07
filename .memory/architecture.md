# Architecture

## 현재 Phase 2 구조

요청 흐름:

1. `PaymentController`가 `POST /api/payments` 요청을 받는다.
2. `PaymentService`가 전체 결제 오케스트레이션을 담당한다.
3. `PaymentInternalService.preparePayment()`가 TX1에서 주문/결제를 `PENDING`으로 저장하고 커밋한다.
4. 트랜잭션 밖에서 `PgClient.approve()`가 외부 PG 승인 API를 호출한다.
5. 결과에 따라 `PaymentInternalService.recordSuccess/recordFailure/recordUnknown()`이 TX2에서 최종 상태를 기록한다.
6. `UnknownPaymentRecoveryScheduler`가 `UNKNOWN` 결제를 PG 조회 API로 복구한다.

## 주요 모듈 책임

- `controller`: HTTP 요청/응답 의미를 담당한다. Phase 2 기준 UNKNOWN은 202, 상태 조회 API가 있다.
- `service/PaymentService`: 트랜잭션 밖 오케스트레이션과 PG 예외 분류를 담당한다.
- `service/PaymentInternalService`: 독립 트랜잭션이 필요한 내부 상태 변경을 담당한다.
- `client/PgClient`: PG 승인/조회 API 호출과 PG 예외 변환을 담당한다.
- `domain`: `Order`, `Payment`, 상태 enum과 상태 전환 메서드를 포함한다.
- `repository`: JPA repository와 테스트 보조 업데이트 메서드를 포함한다.
- `scheduler`: UNKNOWN 상태 복구 작업을 담당한다.
- `config`: RestClient, Scheduling 등 인프라 설정을 분리한다.

## 외부 의존성

- PostgreSQL: 주문/결제 상태 저장.
- WireMock: Phase 1~2와 통합 테스트의 PG 대역.
- Prometheus/Grafana: Actuator metrics와 DB exporter metrics 관측.
- Redis: Phase 3에서 멱등성 키 저장소로 도입 예정.

## Phase 3 예정 구조

- Controller는 `Idempotency-Key` 헤더를 필수로 받고, 누락 시 400, 처리 중 중복 요청은 409를 반환한다.
- `RedisIdempotencyStore`가 `idempotency:{key}` 값에 `PROCESSING` 또는 완료 응답 JSON을 저장한다.
- `PaymentService`는 결제 시작 전 완료 캐시 조회, `SET NX` 획득, 성공 결과 캐싱, 실패 시 키 해제를 담당한다.
- 성공 후 동일 키 재요청은 PG 재호출 없이 캐싱 결과를 반환해야 한다.

## 위험한 경계

- `PaymentInternalService`를 다시 `PaymentService` 내부 메서드로 합치면 트랜잭션 분리가 깨질 수 있다.
- UNKNOWN을 FAILED로 조기 확정하면 실제 승인된 결제를 실패 처리할 수 있다.
- Phase 3에서 키 획득 후 실패 시 release 정책을 잘못 잡으면 안전한 재시도가 막히거나 중복 결제가 발생할 수 있다.
