# Known Issues

## 관측성 검증 미완료

- Phase 1~2 구현과 테스트 통과는 완료 상태로 본다.
- Grafana 대시보드에서 Phase별 핵심 지표가 의도대로 보이는지는 아직 확인 필요하다.
- k6 부하 테스트 시나리오와 실행 결과 검증도 아직 남아 있다.

## Redis 도입 전 상태

- `payment/build.gradle`에는 Redis 관련 구현 의존성이 아직 주석 처리되어 있다.
- `docker-compose.app.yml`에는 Redis 서비스가 아직 없다.
- `application.yaml`에는 Redis 설정이 아직 없다.

## 예외 분류 취약성

- Phase 2 트러블슈팅에서 `PgClient.approve()`가 4xx PG 오류를 네트워크 오류로 오분류한 문제가 있었다.
- 현재는 `PgPaymentException`을 먼저 catch해 메시지를 보존하는 방식으로 해결했지만, 메시지 기반 분류는 여전히 취약하다.
- 향후에는 `NetworkPgException`, `ClientErrorPgException` 또는 error type 필드 기반 분류가 더 견고하다.

## TossPayments Sandbox

- Phase 3 문서에는 TossPayments Sandbox 전환이 포함되어 있으나 테스트 키와 수동 검증 절차는 아직 메모리에 없다.
- 통합 테스트는 계속 WireMock을 사용하고, Sandbox는 별도 프로파일 또는 수동 E2E로 다루는 것이 안전하다.
