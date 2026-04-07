# Phase 2 트러블슈팅 & 회고록

---

## Cycle 8 — PaymentController 확장

### 트러블 1. `@WebMvcTest` import 불가

**증상:**
```
error: package org.springframework.boot.test.autoconfigure.web.servlet does not exist
WebMvcTest cannot be resolved to a type
```

**원인:**
Spring Boot 4.0은 테스트 스타터를 모듈화했다.
기존 `spring-boot-starter-test` 하나로 모든 슬라이스를 커버하던 구조에서,
`@WebMvcTest`는 `spring-boot-starter-webmvc-test`로 분리됐다.
`build.gradle`에 해당 의존성이 이미 있었지만,
`@WebMvcTest`의 패키지 경로가 Spring Boot 4에서 바뀌었음을 인지하지 못했다.

**해결:**
IDE가 올바른 경로로 자동 수정: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`

**교훈:**
Spring Boot 4 마이그레이션 시 테스트 슬라이스 어노테이션의 패키지 경로가 바뀐다.
작업 전 `spring-boot-testing` 스킬의 `sb4-migration.md`를 먼저 확인했다면 초기에 방지할 수 있었다.

---

### 트러블 2. `hasStatusNotFound()` 메서드 없음

**증상:**
```
The method hasStatusNotFound() is undefined for the type MvcTestResultAssert
```

**원인:**
`spring-boot-testing` 스킬의 `mockmvc-tester.md`에 기재된 status assertion 목록이
실제 Spring Framework 7.0.6 jar에 없는 메서드를 포함하고 있었다.

실제 `AbstractHttpServletResponseAssert`에 존재하는 status 메서드:
```
hasStatus(int)
hasStatus(HttpStatus)
hasStatusOk()
hasStatus1xxInformational()
hasStatus2xxSuccessful()
hasStatus3xxRedirection()
hasStatus4xxClientError()
hasStatus5xxServerError()
```

`hasStatusNotFound()`, `hasStatusBadRequest()` 등 개별 4xx 메서드는 **존재하지 않는다.**

**해결:**
```java
// Before (존재하지 않음)
.hasStatusNotFound()

// After (실제 사용 가능)
.hasStatus(HttpStatus.NOT_FOUND)
```

**교훈:**
스킬 문서의 코드 예시는 참고용이지, 실제 jar의 API 명세가 아니다.
확신이 없을 때는 `javap`로 실제 jar를 직접 조회하는 것이 정확하다.

---

## Cycle 9 — UnknownPaymentRecoveryScheduler

### 트러블 3. 테스트 격리 실패 — 공유 시드 데이터 오염

**증상:**
```
expected: PENDING but was: SUCCESS   (whenNetworkError_thenUnknownRetained)
expected: FAILED  but was: SUCCESS   (whenPgNotFound_thenFailed)
```

**원인:**
3개 테스트 모두 시드 데이터의 동일한 `PG-KEY-UNKNOWN-001` UNKNOWN 결제를 공유했다.
JUnit은 테스트 실행 순서를 보장하지 않으며, `whenPgDone_thenSuccess`가 먼저 실행되어
해당 Payment를 SUCCESS로 변경하면 이후 테스트에서 `findByStatus(UNKNOWN)` 결과가 빈 리스트가 된다.

```
테스트 A (PG DONE)   → Payment: UNKNOWN → SUCCESS  (시드 데이터 오염)
테스트 B (PG 404)    → findByStatus(UNKNOWN) = []   → recover() 아무것도 안 함 → 검증 실패
테스트 C (네트워크)  → findByStatus(UNKNOWN) = []   → recover() 아무것도 안 함 → 검증 실패
```

**해결:**
각 테스트가 자신만의 Order + Payment(UNKNOWN)를 `@BeforeEach`에서 직접 생성하고,
`@AfterEach`에서 삭제하여 테스트 간 완전한 격리를 보장했다.

```
@BeforeEach: Order(READY) 저장 → preparePayment() TX1 → recordUnknown() TX2
             → pgPaymentKey를 테스트 전용 키로 업데이트
@AfterEach:  생성한 Payment, Order 삭제
```

`@Transactional` 롤백 방식을 사용하지 않은 이유:
`recordSuccess/recordFailure`가 별도 트랜잭션(`@Transactional`)으로 실행된다.
테스트 메서드에 `@Transactional`을 붙이면 외부 트랜잭션이 열리고,
`paymentInternalService`의 내부 트랜잭션이 외부에 합류(REQUIRED)되어
실제 커밋이 발생하지 않는다. 이후 같은 트랜잭션 내에서 조회해도 변경사항이 보이지 않아 검증 불가.

**추가 발견 — `pgPaymentKey` 설정 문제:**
`preparePayment()` → `recordUnknown()` 으로 생성한 Payment는 `pgPaymentKey`가 null이다.
(`Payment` 생성자가 orderId, amount만 받기 때문)
WireMock stub이 특정 키 경로(`/v1/payments/PG-KEY-TEST-RECOVERY`)를 기다리는데
실제 조회 시 null로 요청이 가서 stub이 매칭되지 않는 문제가 발생했다.

`PaymentRepository`에 테스트 전용 JPQL 업데이트 메서드를 추가하여 해결:
```java
@Modifying
@Transactional
@Query("UPDATE Payment p SET p.pgPaymentKey = :pgPaymentKey WHERE p.id = :paymentId")
void setPaymentKeyForTest(Long paymentId, String pgPaymentKey);
```

**교훈:**
통합 테스트에서 공유 시드 데이터는 테스트 간 독립성을 보장하지 못한다.
여러 테스트가 같은 레코드의 상태를 변경하는 경우, 반드시 테스트별로 독립 데이터를 생성해야 한다.
`@Transactional` 롤백이 만능이 아님을 인식하고, TX 분리 구조에서는 `@AfterEach` 명시적 정리가 안전하다.

---

## 전체 회고

---

## 전체 테스트 통합 실행 시 추가 발견

### 트러블 4. `PgClient.approve()` 4xx 예외가 네트워크 에러로 오분류

**증상:**
```
expected: "FAILED" but was: "UNKNOWN"
```
`PaymentServiceOrchestrationTest.whenPgFails_thenStatusFailed` — PG 400 응답인데 UNKNOWN으로 기록됨.

**원인:**
`PgClient.approve()`의 구조 문제였다.

```java
try {
    return restClient.post()
        ...
        .onStatus(4xx, (req, res) -> {
            throw new PgPaymentException("PG 승인 API 에러: ...");  // ← 여기서 던짐
        })
        .body(...);
} catch (Exception e) {
    // RestClient가 onStatus 핸들러의 예외를 wrap해서 재던지므로
    // PgPaymentException도 이 블록에서 잡혀 메시지가 덮어씌워짐
    throw new PgPaymentException("PG 승인 요청 실패: " + e.getMessage(), e);
}
```

RestClient 내부에서 `onStatus` 핸들러가 던진 예외를 잡아 wrap하여 재던지기 때문에,
바깥 `catch (Exception e)`에서 `PgPaymentException`도 잡혀 메시지가 `"PG 승인 요청 실패:"`로 바뀐다.
`PaymentService.isNetworkError()`는 `"PG 승인 요청 실패:"`로 시작하면 네트워크 에러로 판단하므로
4xx도 UNKNOWN으로 잘못 처리됐다.

**해결:**
`catch` 블록에서 `PgPaymentException`을 먼저 잡아 그대로 재던짐으로써 메시지를 보존한다.

```java
} catch (PgPaymentException e) {
    throw e;  // 4xx 에러 메시지("PG 승인 API 에러:") 보존
} catch (Exception e) {
    throw new PgPaymentException("PG 승인 요청 실패: " + e.getMessage(), e);  // 네트워크 에러
}
```

**교훈:**
예외 메시지로 에러 종류를 구분하는 패턴은 이런 wrap 문제에 취약하다.
예외 타입 자체를 분리(`NetworkPgException`, `ClientErrorPgException`)하거나
예외에 `ErrorType` 필드를 두는 것이 더 견고한 설계다.

---

### 잘 된 점

- **TDD 사이클 준수**: 테스트 먼저 작성(Red) → 프로덕션 코드 작성(Green) 순서를 지켰다.
- **슬라이스 테스트 선택**: Controller 테스트에 `@SpringBootTest` 대신 `@WebMvcTest`를 고집한 것이 맞았다. 전체 컨텍스트 로딩 없이 HTTP 시멘틱만 빠르게 검증할 수 있었다.
- **SchedulingConfig 분리**: `@EnableScheduling`을 `PaymentApplication`에 붙이지 않고 별도 설정 클래스로 분리한 덕분에, 테스트 컨텍스트 로딩 시 스케줄러 자동 실행 간섭이 없었다.

### 아쉬운 점

- **Spring Boot 4 API 변경 사전 인지 부족**: `@WebMvcTest` 패키지 경로, `hasStatusNotFound()` 미존재 모두 사전에 jar를 확인했다면 피할 수 있었다. 새 버전 작업 전 마이그레이션 문서 확인이 먼저여야 한다.
- **테스트 데이터 격리 설계 부재**: 여러 테스트가 상태를 변경하는 공유 시드 데이터를 사용할 때 격리 전략을 처음부터 설계했어야 했다. `@BeforeEach` / `@AfterEach` 패턴은 처음 설계 단계에서 결정되었어야 할 사항이다.
