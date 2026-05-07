# Troubleshooting

## Spring Boot 4 `@WebMvcTest` import 문제

- 증상: `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` import가 실패한다.
- 원인: Spring Boot 4에서 테스트 슬라이스 패키지 구조가 바뀌었다.
- 해결: 실제 IDE/JAR 기준 import를 사용한다. 기존 문서의 Boot 3 import를 그대로 믿지 않는다.

## MockMvcTester status assertion shortcut 없음

- 증상: `hasStatusNotFound()`, `hasStatusBadRequest()` 등이 없다는 컴파일 오류.
- 원인: 실제 Spring Framework 7.0.6 assert API에 개별 4xx shortcut이 없다.
- 해결: `hasStatus(HttpStatus.NOT_FOUND)`, `hasStatus(HttpStatus.BAD_REQUEST)`처럼 명시적 status를 사용한다.

## 공유 시드 데이터 오염

- 증상: 전체 테스트 실행 시 단독 테스트와 다른 상태가 조회되어 실패한다.
- 원인: 여러 테스트가 동일 시드 레코드의 상태를 변경한다.
- 해결: 상태 변경 통합 테스트는 각 테스트가 독립 Order/Payment를 생성하고 `@AfterEach`에서 삭제한다.

## TX 분리 테스트에서 `@Transactional` 롤백이 방해되는 경우

- 증상: 내부 서비스의 별도 트랜잭션 변경사항이 검증 시 기대처럼 보이지 않는다.
- 원인: 테스트 메서드의 외부 트랜잭션과 서비스의 `REQUIRED` 트랜잭션이 합류해 실제 커밋/조회 타이밍이 달라진다.
- 해결: 테스트 메서드에 `@Transactional`을 붙이는 대신 명시적으로 데이터를 생성/정리한다.

## PG 4xx가 UNKNOWN으로 기록되는 문제

- 증상: PG 400 응답이 `FAILED`가 아니라 `UNKNOWN`으로 기록된다.
- 원인: `onStatus`에서 던진 `PgPaymentException`이 바깥 `catch (Exception)`에 잡혀 네트워크 오류 메시지로 덮인다.
- 해결: `catch (PgPaymentException e) { throw e; }`를 먼저 두고, 그 외 예외만 네트워크 오류로 감싼다.
