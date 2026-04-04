# Phase 4. 보상 트랜잭션 (Compensating Transaction)

> "결제는 성공했는데 쿠폰 차감 중 에러가 발생했다. 어떻게 환불할 것인가?"

---

## 이 Phase의 목표

- 결제 이후 **쿠폰 차감, 포인트 사용** 등 다단계 처리를 추가한다
- 중간 단계 실패 시 **보상 트랜잭션(역순 취소)**을 구현한다
- 보상 자체가 실패하는 경우를 경험하고, 동기 호출 체인의 한계를 확인한다

---

## 이전 Phase와의 차이 — 복잡도 증가

```
Phase 1~3: 결제만 처리
  POST /api/payments → PG 승인

Phase 4: 다단계 파이프라인
  POST /api/payments
    → 1. 쿠폰 예약 (쿠폰 서비스)
    → 2. PG 승인 (TossPayments)
    → 3. 쿠폰 확정 (쿠폰 서비스)
    → 4. 포인트 차감 (포인트 서비스)
    → 어딘가에서 실패하면? 역순으로 모두 취소해야 한다
```

---

## 4-1. 쿠폰/포인트 서비스 (WireMock으로 시뮬레이션)

> 실제 마이크로서비스 대신 WireMock으로 외부 시스템의 동작을 시뮬레이션한다.
> 이렇게 하면 의도적으로 장애를 주입하기 쉽다.

### WireMock 매핑 — 쿠폰 서비스

```json
// wiremock/mappings/coupon-reserve.json
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/coupons/reserve"
  },
  "response": {
    "status": 200,
    "jsonBody": { "couponId": "CPN-001", "status": "RESERVED", "discountAmount": 3000 }
  }
}
```

```json
// wiremock/mappings/coupon-confirm.json
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/coupons/confirm"
  },
  "response": {
    "status": 200,
    "jsonBody": { "couponId": "CPN-001", "status": "USED" }
  }
}
```

```json
// wiremock/mappings/coupon-cancel.json (보상용)
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/coupons/cancel"
  },
  "response": {
    "status": 200,
    "jsonBody": { "couponId": "CPN-001", "status": "RELEASED" }
  }
}
```

### WireMock 매핑 — 포인트 서비스

```json
// wiremock/mappings/point-deduct.json
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/points/deduct"
  },
  "response": {
    "status": 200,
    "jsonBody": { "userId": 1, "deducted": 1000, "remaining": 9000 }
  }
}
```

```json
// wiremock/mappings/point-restore.json (보상용)
{
  "request": {
    "method": "POST",
    "urlPattern": "/api/points/restore"
  },
  "response": {
    "status": 200,
    "jsonBody": { "userId": 1, "restored": 1000, "remaining": 10000 }
  }
}
```

---

## 4-2. 외부 서비스 클라이언트

```java
@Component
@RequiredArgsConstructor
public class CouponClient {

    private final RestTemplate restTemplate;

    @Value("${coupon.base-url}")
    private String baseUrl;

    public CouponReserveResponse reserve(String couponId, String orderNumber) {
        return restTemplate.postForObject(
            baseUrl + "/api/coupons/reserve",
            Map.of("couponId", couponId, "orderNumber", orderNumber),
            CouponReserveResponse.class
        );
    }

    public void confirm(String couponId) {
        restTemplate.postForObject(
            baseUrl + "/api/coupons/confirm",
            Map.of("couponId", couponId),
            Void.class
        );
    }

    /** 보상: 쿠폰 예약 취소 */
    public void cancel(String couponId) {
        restTemplate.postForObject(
            baseUrl + "/api/coupons/cancel",
            Map.of("couponId", couponId),
            Void.class
        );
    }
}

public record CouponReserveResponse(String couponId, String status, Long discountAmount) {}
```

```java
@Component
@RequiredArgsConstructor
public class PointClient {

    private final RestTemplate restTemplate;

    @Value("${point.base-url}")
    private String baseUrl;

    public void deduct(Long userId, Long amount) {
        restTemplate.postForObject(
            baseUrl + "/api/points/deduct",
            Map.of("userId", userId, "amount", amount),
            Void.class
        );
    }

    /** 보상: 포인트 복구 */
    public void restore(Long userId, Long amount) {
        restTemplate.postForObject(
            baseUrl + "/api/points/restore",
            Map.of("userId", userId, "amount", amount),
            Void.class
        );
    }
}
```

---

## 4-3. 보상 트랜잭션이 포함된 결제 파이프라인

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrchestrator {

    private final PaymentInternalService paymentInternalService;
    private final PgClient pgClient;
    private final CouponClient couponClient;
    private final PointClient pointClient;
    private final RedisIdempotencyStore idempotencyStore;
    private final MeterRegistry meterRegistry;

    /**
     * 다단계 결제 파이프라인
     *
     * 실행 순서: 쿠폰 예약 → PG 승인 → 쿠폰 확정 → 포인트 차감
     * 실패 시: 역순으로 보상 (포인트 복구 → 쿠폰 취소 → PG 환불)
     */
    public PaymentResult processPaymentWithCouponAndPoint(
            PaymentWithCouponRequest request, String idempotencyKey) {

        // 멱등성 검증 (Phase 3)
        if (!idempotencyStore.tryAcquire(idempotencyKey)) {
            throw new DuplicatePaymentException("중복 요청: " + idempotencyKey);
        }

        // 각 단계 성공 여부 추적 (보상 범위 결정용)
        boolean couponReserved = false;
        boolean pgApproved = false;
        boolean couponConfirmed = false;
        String pgPaymentKey = null;

        try {
            // === Step 1: 쿠폰 예약 ===
            if (request.couponId() != null) {
                couponClient.reserve(request.couponId(), request.orderNumber());
                couponReserved = true;
                log.info("Step 1 완료: 쿠폰 예약 ({})", request.couponId());
            }

            // === Step 2: 주문 PENDING + PG 승인 ===
            PaymentPendingResult pending = paymentInternalService.createPendingPayment(
                request.orderNumber()
            );
            PgApproveResponse pgResponse = pgClient.approve(
                pending.orderNumber(), pending.amount()
            );
            pgApproved = true;
            pgPaymentKey = pgResponse.paymentKey();
            log.info("Step 2 완료: PG 승인 ({})", pgPaymentKey);

            // === Step 3: 쿠폰 확정 ===
            if (request.couponId() != null) {
                couponClient.confirm(request.couponId());
                couponConfirmed = true;
                log.info("Step 3 완료: 쿠폰 확정");
            }

            // === Step 4: 포인트 차감 ===
            if (request.pointAmount() != null && request.pointAmount() > 0) {
                pointClient.deduct(request.userId(), request.pointAmount());
                log.info("Step 4 완료: 포인트 차감 ({}원)", request.pointAmount());
            }

            // === 모든 단계 성공 ===
            paymentInternalService.completePayment(pending.paymentId(), pgPaymentKey);
            meterRegistry.counter("payment.pipeline.success").increment();

            return new PaymentResult(request.orderNumber(), "SUCCESS", pgPaymentKey);

        } catch (Exception e) {
            log.error("결제 파이프라인 실패 — 보상 트랜잭션 시작", e);
            meterRegistry.counter("payment.pipeline.failed").increment();

            // === 보상 트랜잭션: 역순으로 취소 ===
            compensate(request, pgPaymentKey, couponReserved, pgApproved, couponConfirmed);

            idempotencyStore.release(idempotencyKey);
            throw new PaymentPipelineException("결제 파이프라인 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 보상 트랜잭션 — 성공한 단계를 역순으로 취소한다.
     *
     * ⚠️ 보상 자체도 실패할 수 있다. (네트워크 오류, 서비스 장애 등)
     * 이 경우 데이터가 불일치 상태로 남는다 → Phase 5에서 해결
     */
    private void compensate(PaymentWithCouponRequest request, String pgPaymentKey,
                            boolean couponReserved, boolean pgApproved, boolean couponConfirmed) {

        // 포인트 복구 (Step 4의 역)
        if (request.pointAmount() != null && request.pointAmount() > 0) {
            try {
                pointClient.restore(request.userId(), request.pointAmount());
                log.info("보상: 포인트 복구 완료");
                meterRegistry.counter("payment.compensation.point.success").increment();
            } catch (Exception e) {
                log.error("⚠️ 보상 실패: 포인트 복구 실패!", e);
                meterRegistry.counter("payment.compensation.point.failed").increment();
            }
        }

        // 쿠폰 취소 (Step 1/3의 역)
        if (couponReserved) {
            try {
                couponClient.cancel(request.couponId());
                log.info("보상: 쿠폰 취소 완료");
                meterRegistry.counter("payment.compensation.coupon.success").increment();
            } catch (Exception e) {
                log.error("⚠️ 보상 실패: 쿠폰 취소 실패!", e);
                meterRegistry.counter("payment.compensation.coupon.failed").increment();
            }
        }

        // PG 환불 (Step 2의 역)
        if (pgApproved && pgPaymentKey != null) {
            try {
                pgClient.cancel(pgPaymentKey, "파이프라인 실패로 인한 자동 환불");
                log.info("보상: PG 환불 완료 ({})", pgPaymentKey);
                meterRegistry.counter("payment.compensation.pg.success").increment();
            } catch (Exception e) {
                log.error("⚠️ 보상 실패: PG 환불 실패! paymentKey={}", pgPaymentKey, e);
                meterRegistry.counter("payment.compensation.pg.failed").increment();
                // → 이 경우 고객 돈이 빠진 채 복구되지 않는다.
                // → Phase 5에서 이 문제를 해결한다.
            }
        }
    }
}

public record PaymentWithCouponRequest(
    String orderNumber,
    Long userId,
    String couponId,
    Long pointAmount
) {}
```

### PgClient — 환불 메서드 추가

```java
// PgClient에 추가
public void cancel(String paymentKey, String cancelReason) {
    restTemplate.postForObject(
        pgBaseUrl + "/v1/payments/" + paymentKey + "/cancel",
        Map.of("cancelReason", cancelReason),
        Void.class
    );
}
```

---

## 4-4. TDD — 보상 트랜잭션 검증

### 테스트 1: 정상 흐름 — 전체 파이프라인 성공

```java
@Test
@DisplayName("쿠폰 + 결제 + 포인트 전체 파이프라인이 정상 동작한다")
void fullPipeline_success() {
    // 모든 WireMock stub이 정상 200 응답
    PaymentResult result = orchestrator.processPaymentWithCouponAndPoint(
        new PaymentWithCouponRequest("ORD-001", 1L, "CPN-001", 1000L),
        "idem-full-success"
    );

    assertThat(result.status()).isEqualTo("SUCCESS");

    // 각 서비스 호출 확인
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/coupons/reserve")));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/payments/confirm")));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/coupons/confirm")));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/points/deduct")));

    // 보상 API는 호출되지 않아야 한다
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/coupons/cancel")));
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/points/restore")));
}
```

### 테스트 2: 포인트 차감 실패 → PG 환불 + 쿠폰 취소

```java
@Test
@DisplayName("포인트 차감 실패 시 PG 환불과 쿠폰 취소가 자동으로 실행된다")
void pointDeductionFails_triggers_compensation() {
    // 포인트 서비스만 500 에러로 설정
    wireMock.stubFor(post(urlPathEqualTo("/api/points/deduct"))
        .willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() ->
        orchestrator.processPaymentWithCouponAndPoint(
            new PaymentWithCouponRequest("ORD-002", 1L, "CPN-001", 1000L),
            "idem-point-fail"
        )
    ).isInstanceOf(PaymentPipelineException.class);

    // 보상 API 호출 확인
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/points/restore")));  // 포인트 복구
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/coupons/cancel")));  // 쿠폰 취소
    wireMock.verify(1, postRequestedFor(
        urlPathMatching("/v1/payments/.*/cancel")));                               // PG 환불

    System.out.println("=== 보상 트랜잭션 테스트 ===");
    System.out.println("실패 단계: 포인트 차감");
    System.out.println("보상 실행: PG 환불 ✅, 쿠폰 취소 ✅, 포인트 복구 ✅");
}
```

### 테스트 3: 보상 자체가 실패하는 경우

```java
@Test
@DisplayName("보상 트랜잭션(PG 환불)마저 실패하면 데이터가 불일치 상태로 남는다")
void compensation_itself_fails_leaves_inconsistency() {
    // Step 4(포인트 차감) 실패
    wireMock.stubFor(post(urlPathEqualTo("/api/points/deduct"))
        .willReturn(aResponse().withStatus(500)));

    // 보상 Step: PG 환불도 실패 (네트워크 오류 시뮬레이션)
    wireMock.stubFor(post(urlPathMatching("/v1/payments/.*/cancel"))
        .willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() ->
        orchestrator.processPaymentWithCouponAndPoint(
            new PaymentWithCouponRequest("ORD-003", 1L, "CPN-001", 1000L),
            "idem-compensation-fail"
        )
    ).isInstanceOf(PaymentPipelineException.class);

    // PG 환불이 시도되었지만 실패함
    wireMock.verify(1, postRequestedFor(urlPathMatching("/v1/payments/.*/cancel")));

    System.out.println("=== 보상 실패 테스트 ===");
    System.out.println("파이프라인 실패: 포인트 차감 에러");
    System.out.println("보상 결과: PG 환불 ❌ (500 에러)");
    System.out.println("→ 고객 돈은 빠졌는데 환불도 안 됨 = 데이터 불일치!");
    System.out.println("→ Phase 5(Outbox + Kafka)에서 이 문제를 해결해야 한다.");
}
```

---

## 4-5. Grafana 모니터링 패널

```
Row 1: 파이프라인 결과
  - payment.pipeline.success   (전체 성공)
  - payment.pipeline.failed    (파이프라인 실패 → 보상 트리거)

Row 2: 보상 트랜잭션 결과
  - payment.compensation.pg.success / failed      (PG 환불 성공/실패)
  - payment.compensation.coupon.success / failed   (쿠폰 취소 성공/실패)
  - payment.compensation.point.success / failed    (포인트 복구 성공/실패)

Row 3: 응답 시간 (서비스 수 증가에 따른 지연 누적)
  - http_client_requests_seconds {uri="/api/coupons/*"}
  - http_client_requests_seconds {uri="/v1/payments/*"}
  - http_client_requests_seconds {uri="/api/points/*"}
  - 전체 파이프라인 응답 시간 = 각 서비스 응답 시간의 합
```

---

## Phase 3과의 비교 측정표

| 항목 | Phase 3 (멱등성) | Phase 4 (보상 트랜잭션) |
|------|-----------------|----------------------|
| 외부 서비스 수 | PG 1개 | PG + 쿠폰 + 포인트 (3개) |
| 실패 시 복구 | 없음 (단일 결제) | 보상 트랜잭션 (역순 취소) |
| 전체 응답 시간 | PG 1회 호출 | 외부 4회 호출 (누적) |
| 결합도 | 낮음 | 높음 (동기 체인) |
| 하나의 서비스 장애 시 | 결제만 실패 | **전체 파이프라인 실패** |
| 보상 실패 시 | - | 데이터 영구 불일치 |

---

## 여기서 얻는 인사이트

- 분산 환경에서 **DB 트랜잭션 같은 "롤백"은 존재하지 않는다** — 보상으로 대체해야 한다
- 보상 트랜잭션의 한계: **보상 자체가 실패하면 데이터가 영원히 불일치** 상태로 남는다
- 동기 호출 체인: 서비스가 3개로 늘어나면 응답 시간 = 3개 응답의 합 (강결합)
- 하나의 서비스 장애 → 전체 결제 실패 (연쇄 장애 위험)

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: 파이프라인 성공률 ***%, 보상 성공률 ***%, 전체 응답 시간 Phase 3 대비 ***배 증가

---

## ❓ 남은 문제 → Phase 5로

> "보상이 실패하면 데이터가 영원히 불일치다. 동기 체인은 하나만 느려져도 전체가 느려진다. 서비스 간 결합을 끊을 수 없을까?"

→ [[phase5-outbox-kafka]]
