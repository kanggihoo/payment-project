# Decisions

## Phase 2 트랜잭션 경계 분리

- 날짜: 2026-05-08
- 상태: active
- 결정: 외부 PG 호출은 DB 트랜잭션 밖에서 실행하고, TX1 준비 저장과 TX2 결과 기록을 `PaymentInternalService` 별도 빈으로 분리한다.
- 이유: `PaymentService` 내부 self-invocation은 Spring AOP 프록시를 우회해 `@Transactional` 경계가 적용되지 않는다. 별도 빈 호출이어야 독립 트랜잭션으로 동작한다.
- 버린 대안: `PaymentService` 내부 private/public 메서드에 `@Transactional`을 붙이고 `this.method()`로 호출하는 방식.
- 재검토 조건: 트랜잭션 관리 방식을 AOP 프록시가 아닌 명시적 `TransactionTemplate` 등으로 바꿀 때.

## UNKNOWN 상태 처리

- 날짜: 2026-05-08
- 상태: active
- 결정: PG 응답 타임아웃 또는 네트워크 단절은 실패로 확정하지 않고 `PaymentStatus.UNKNOWN`, 주문은 `PENDING`으로 유지한다.
- 이유: 응답을 받지 못했다는 사실은 PG 승인 실패를 의미하지 않는다. 실제 승인 여부는 PG 조회 API polling으로 확정해야 한다.
- 버린 대안: 타임아웃을 즉시 `FAILED`로 기록하는 방식.
- 재검토 조건: PG가 멱등성과 조회 일관성을 보장하지 않거나, 승인 요청의 전송 여부를 확실히 판단할 수 있는 다른 프로토콜을 도입할 때.

## 스케줄링 설정 분리

- 날짜: 2026-05-08
- 상태: active
- 결정: `@EnableScheduling`은 `PaymentApplication`이 아니라 `SchedulingConfig`에 둔다.
- 이유: 전체 Spring Boot 테스트 컨텍스트 로딩 시 복구 스케줄러가 자동 실행되어 테스트 데이터를 오염시킬 수 있다.
- 버린 대안: 애플리케이션 메인 클래스에 `@EnableScheduling`을 직접 선언하는 방식.
- 재검토 조건: 테스트 프로파일에서 스케줄러를 확실히 비활성화하는 별도 운영 규칙이 생길 때.

## Phase 3 멱등성 우선 구현

- 날짜: 2026-05-08
- 상태: active
- 결정: Phase 3의 첫 구현은 Redis `SET NX` 기반 멱등성 저장소를 우선한다.
- 이유: 동시성 프로젝트 지식 재활용이 가능하고, 동일 키 동시 요청을 원자적으로 차단하기 쉽다. TTL 기반 만료도 자연스럽다.
- 버린 대안: DB UNIQUE INDEX 기반 멱등성 테이블을 먼저 구현하는 방식.
- 재검토 조건: Redis 운영 의존성을 줄여야 하거나, 결제 응답 캐싱/감사 이력을 DB에 강하게 남겨야 할 때.
