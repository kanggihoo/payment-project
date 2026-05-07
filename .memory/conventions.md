# Conventions

## Tech Stack

- Java 21
- Spring Boot 4.0.5
- Gradle 8.x
- Spring Web MVC, Spring Data JPA, Validation, Actuator
- PostgreSQL 17-alpine
- WireMock for integration tests and Phase 1~2 PG simulation
- Prometheus + Grafana for metrics
- k6 for load tests
- Redis 7.2-alpine from Phase 3
- Kafka, Kafka Connect, Debezium from later phases

## 작업 방식

- Phase별 문서와 현재 메모리를 먼저 보고 필요한 범위의 코드만 확인한다.
- 구현은 문서의 TDD 사이클 단위를 지킨다.
- 테스트가 필요한 구현 후에는 관련 테스트를 먼저 실행하고, 범위가 넓으면 전체 테스트를 마지막에 실행한다.
- Phase 3 작업은 `docs/PLAN-phase3-ide.md`의 Cycle 순서를 우선한다.

## Spring Boot 4 테스트 규칙

- Controller slice test는 가능한 `@WebMvcTest`를 사용한다.
- Spring Boot 4의 `@WebMvcTest` 패키지는 기존 Boot 3과 다를 수 있으므로 실제 import를 확인한다.
- MockMvcTester status assertion은 실제 jar API를 기준으로 쓴다. `hasStatusNotFound()` 같은 개별 4xx shortcut이 없으면 `hasStatus(HttpStatus.NOT_FOUND)`를 사용한다.
- 통합 테스트에서 상태를 변경하는 데이터는 공유 시드 데이터에 의존하지 말고 테스트별로 생성/정리한다.

## 트랜잭션/테스트 격리

- TX 분리 구조에서는 테스트 메서드에 `@Transactional`을 붙여 롤백으로 격리하는 방식이 내부 서비스 트랜잭션 검증과 충돌할 수 있다.
- 상태 변경 통합 테스트는 `@BeforeEach`에서 독립 데이터를 만들고 `@AfterEach`에서 명시적으로 정리하는 방식을 우선한다.

## 코드 스타일

- 기존 패키지 구조와 Lombok 사용 방식을 따른다.
- 예외 종류를 메시지 문자열로만 구분하는 방식은 취약하다. 새로 설계할 때는 가능하면 예외 타입이나 명시적 error type을 둔다.
- 주석은 복잡한 Phase 학습 포인트나 트랜잭션/멱등성 경계 설명이 필요한 곳에만 쓴다.
