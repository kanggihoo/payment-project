# Project Brief

## 목표

결제 시스템을 가장 단순한 구현에서 시작해 Phase 1~7까지 점진적으로 고도화한다.
각 Phase는 이전 단계에서 실제로 관찰한 문제를 해결하고, 그 해결책이 만드는 다음 문제를 다음 Phase의 동기로 삼는다.

핵심 학습 목표는 "외부 시스템이 포함된 결제 흐름에서 데이터가 결국 일치하도록 만드는 방법"을 코드, 테스트, 부하, 모니터링으로 검증하는 것이다.

## 로드맵

- Phase 1: 나이브한 결제. 외부 PG 호출을 `@Transactional` 안에 넣었을 때 커넥션 풀 고갈과 데이터 불일치를 관찰한다.
- Phase 2: 트랜잭션 분리 + 상태 머신. `PENDING/SUCCESS/FAILED/UNKNOWN` 상태와 PG 조회 기반 복구를 도입한다.
- Phase 3: 멱등성 + TossPayments Sandbox. `Idempotency-Key`와 Redis `SET NX`로 중복 결제와 재시도 문제를 방어한다.
- Phase 4: 보상 트랜잭션. 결제 + 쿠폰 + 포인트 등 다단계 처리 실패를 역순 보상으로 복구한다.
- Phase 5: Outbox + Kafka. DB 커밋과 이벤트 발행의 원자성을 보장하고 동기 호출 결합을 줄인다.
- Phase 6: CDC + Debezium. Outbox 폴링을 WAL 기반 CDC로 대체해 지연과 DB 부하를 줄인다.
- Phase 7: DLQ + Reconciliation + 운영 모니터링. 실패 이벤트 격리, 재처리, 대사 작업으로 최종 일관성을 운영 레벨에서 보장한다.

## 현재 제품 경계

- Phase 1~3은 단일 결제 흐름 중심이다.
- Phase 4부터 쿠폰/포인트 외부 시스템이 추가된다.
- Phase 5부터 알림과 Kafka 기반 비동기 이벤트 흐름이 추가된다.
- 통합 테스트는 WireMock 기반을 유지하고, TossPayments Sandbox는 Phase 3 이후 수동 E2E 또는 별도 프로파일에서 확인한다.

## 주요 자료

- 전체 로드맵: `README.md`
- Phase별 상세 문서: `docs/`
- Phase 3 상세 구현 계획: `docs/PLAN-phase3-ide.md`
