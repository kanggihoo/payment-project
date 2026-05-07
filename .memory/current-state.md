# Current State

## 현재 위치

- 프로젝트는 Phase 1~7 로드맵으로 진행된다.
- Phase 1과 Phase 2는 구현, 테스트 코드 통과, 검증이 완료된 상태로 본다.
- 다만 Grafana 대시보드 시각화와 k6 부하 테스트 검증은 아직 확인이 필요하다.
- 다음 구현 진입점은 Phase 3: Redis 기반 멱등성 + `Idempotency-Key` 도입이다.

## 현재 코드 상태 요약

- Spring Boot 4.0.5, Java 21, Gradle 기반 프로젝트다.
- 현재 앱 인프라는 PostgreSQL, postgres-exporter, WireMock이 `docker-compose.app.yml`에 있다.
- 모니터링 인프라는 Prometheus, Grafana, k6가 `docker-compose.monitoring.yml`에 있다.
- `application.yaml`은 PostgreSQL, WireMock PG base URL, Actuator Prometheus endpoint를 설정한다.
- Redis 의존성과 Redis compose 서비스는 아직 활성화되어 있지 않다.

## 바로 볼 파일

- Phase 3 구현 전: `docs/PLAN-phase3-ide.md`
- Phase 2 완료 맥락: `docs/phase2-progress.md`
- Phase 2 트러블슈팅: `docs/phase2-troubleshooting.md`
- 현재 코드 진입점:
  - `payment/src/main/java/com/example/payment/service/PaymentService.java`
  - `payment/src/main/java/com/example/payment/service/PaymentInternalService.java`
  - `payment/src/main/java/com/example/payment/controller/PaymentController.java`
  - `payment/src/main/java/com/example/payment/client/PgClient.java`

## 주의

- 세션 시작 시 전체 소스 탐색이나 테스트 실행을 기본으로 하지 않는다.
- Phase 3는 TDD 사이클 단위로 진행하는 계획이 있으므로, 구현 시 해당 사이클 범위를 넘기지 않는다.
- 사용자는 Phase 1~2 테스트 통과를 완료 상태로 제공했지만, Grafana/k6 관측성 검증은 별도 남은 작업이다.
