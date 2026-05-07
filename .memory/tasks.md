# Tasks

## 다음 작업 큐

1. Phase 1~2 관측성 검증
   - Grafana에서 Phase 1 핵심 지표 `hikaricp_connections_active` 시각화 확인.
   - Phase 2 핵심 지표 `payment.state.pending` 또는 이에 대응하는 커스텀 메트릭 존재 여부 확인.
   - k6 시나리오와 실행 절차가 실제 파일로 준비되어 있는지 확인.

2. Phase 3 구현 시작
   - `docs/PLAN-phase3-ide.md`를 기준으로 Cycle 0부터 진행.
   - `payment/build.gradle`에 Redis 의존성 활성화.
   - `payment/src/main/resources/application.yaml`에 Redis 설정 추가.
   - `docker-compose.app.yml`에 Redis 7.2-alpine 서비스 추가.

3. Phase 3 TDD 사이클
   - Cycle 1: `DuplicatePaymentException`, `Idempotency-Key` 헤더, Controller 409/400 응답.
   - Cycle 2: `RedisIdempotencyStore` 단위 테스트 및 구현.
   - Cycle 3: `PaymentService`에 Redis 멱등성 통합.
   - Cycle 4: 성공 후 동일 키 재시도 시 캐싱된 결과 반환.
   - Cycle 5: 100건 동시 요청 중 1건만 PG 도달하는 동시성 테스트.
   - Cycle 6 선택: TossPayments Sandbox 클라이언트와 수동 E2E 검증.

## 보류/확인 필요

- TossPayments Sandbox 테스트 키와 실제 E2E 실행 방식은 사용자의 별도 확인이 필요하다.
- Phase 3 문서에는 DB UNIQUE INDEX 방식도 언급되지만, 상세 구현 계획은 Redis `SET NX`를 우선한다.
- k6 스크립트 디렉터리와 Grafana 대시보드 파일이 없으면 새로 작성해야 한다.
