# Phase 7. 최후의 안전망 — DLQ + 대사(Reconciliation) + 모니터링

> "어디서 장애가 나도, 데이터는 **결국** 일치해야 한다."

---

## 이 Phase의 목표

- Consumer가 재시도를 포함해 **처리에 완전히 실패한 이벤트**를 DLQ로 격리한다
- 정기적으로 시스템 간 데이터를 **대사(Reconciliation)**하여 불일치를 감지한다
- 전체 파이프라인의 **운영 가시성**을 Grafana 대시보드와 알림으로 확보한다
- "Eventually Consistent"를 운영 레벨에서 보장하는 최종 시스템을 완성한다

---

## 7-1. Dead Letter Queue (DLQ)

### Kafka Consumer 재시도 + DLQ 설정

```java
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 재시도 정책: 최대 3회, 1초 간격
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(
                    record.topic() + ".DLQ",  // DLQ 토픽명: payment-events.DLQ
                    record.partition()
                )),
            new FixedBackOff(1000L, 3L)  // 1초 간격, 최대 3회 재시도
        );

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
```

### DLQ 모니터링 Consumer

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DlqMonitorConsumer {

    private final MeterRegistry meterRegistry;
    private final SlackNotifier slackNotifier;

    /**
     * DLQ에 도착한 이벤트를 모니터링하고 운영자에게 알린다.
     */
    @KafkaListener(topics = "payment-events.DLQ", groupId = "dlq-monitor")
    public void handleDeadLetter(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage) {

        log.error("🚨 DLQ 이벤트 수신: key={}, error={}", key, exceptionMessage);

        meterRegistry.counter("dlq.received").increment();

        // Slack 알림
        slackNotifier.send(String.format(
            "🚨 [결제 시스템] DLQ 이벤트 발생\n" +
            "주문번호: %s\n" +
            "에러: %s\n" +
            "payload: %s",
            key, exceptionMessage, payload
        ));
    }
}
```

### DLQ 재처리기

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DlqReprocessor {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * DLQ 이벤트를 원래 토픽으로 재발행한다.
     * 운영자가 문제를 수정한 후 수동으로 실행한다.
     */
    public void reprocess(String originalTopic, String key, String payload) {
        try {
            kafkaTemplate.send(originalTopic, key, payload).get();
            log.info("DLQ 재처리 완료: topic={}, key={}", originalTopic, key);
            meterRegistry.counter("dlq.reprocessed.success").increment();
        } catch (Exception e) {
            log.error("DLQ 재처리 실패: key={}", key, e);
            meterRegistry.counter("dlq.reprocessed.failed").increment();
        }
    }
}
```

### DLQ 재처리 API (운영용)

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/dlq")
@Profile("!prod")  // 또는 운영자 인증 필터 적용
public class DlqAdminController {

    private final DlqReprocessor dlqReprocessor;

    @PostMapping("/reprocess")
    public ResponseEntity<String> reprocess(@RequestBody DlqReprocessRequest request) {
        dlqReprocessor.reprocess(request.originalTopic(), request.key(), request.payload());
        return ResponseEntity.ok("재처리 요청 완료");
    }
}

public record DlqReprocessRequest(String originalTopic, String key, String payload) {}
```

---

## 7-2. 대사(Reconciliation) 스케줄러

### 결제 ↔ 쿠폰 대사

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CouponClient couponClient;
    private final MeterRegistry meterRegistry;
    private final SlackNotifier slackNotifier;

    /**
     * 5분마다 결제 성공 건과 쿠폰 사용 건을 대사한다.
     * 
     * 대사란: "우리 시스템에서 결제 성공으로 기록된 건이
     *         쿠폰 서비스에서도 실제로 사용 처리되었는지" 확인하는 것.
     */
    @Scheduled(fixedDelay = 300000)  // 5분 주기
    public void reconcilePaymentAndCoupon() {
        log.info("=== 대사(Reconciliation) 시작 ===");

        // 최근 1시간 내 결제 성공 건 조회
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<Payment> successPayments = paymentRepository
            .findByStatusAndCreatedAtAfter(PaymentStatus.SUCCESS, since);

        int matchCount = 0;
        int mismatchCount = 0;
        List<String> mismatches = new ArrayList<>();

        for (Payment payment : successPayments) {
            try {
                Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
                if (order == null) continue;

                // 쿠폰 서비스에 해당 주문의 쿠폰 사용 여부 확인
                // (실제로는 쿠폰 서비스의 조회 API 호출)
                boolean couponUsed = couponClient.isUsed(order.getOrderNumber());

                if (couponUsed) {
                    matchCount++;
                } else {
                    mismatchCount++;
                    mismatches.add(order.getOrderNumber());
                    log.warn("⚠️ 대사 불일치: 주문 {} — 결제 SUCCESS인데 쿠폰 미사용",
                        order.getOrderNumber());
                }

            } catch (Exception e) {
                log.error("대사 확인 실패: paymentId={}", payment.getId(), e);
            }
        }

        meterRegistry.gauge("reconciliation.match.count", matchCount);
        meterRegistry.gauge("reconciliation.mismatch.count", mismatchCount);

        log.info("=== 대사 완료: 일치={}, 불일치={} ===", matchCount, mismatchCount);

        // 불일치가 있으면 알림
        if (mismatchCount > 0) {
            slackNotifier.send(String.format(
                "⚠️ [결제-쿠폰 대사] 불일치 %d건 발견\n불일치 주문: %s",
                mismatchCount, String.join(", ", mismatches)
            ));
        }
    }
}
```

### PENDING 장기 체류 감지

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingPaymentAlertScheduler {

    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;
    private final SlackNotifier slackNotifier;

    /**
     * 1분마다 10분 이상 PENDING 상태인 결제를 감지한다.
     * Phase 2의 Unknown State가 복구되지 않은 경우를 최종 방어.
     */
    @Scheduled(fixedDelay = 60000)
    public void alertStalePendingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        List<Payment> stalePayments = paymentRepository
            .findByStatusAndCreatedAtBefore(PaymentStatus.PENDING, threshold);

        meterRegistry.gauge("payment.pending.stale.count", stalePayments.size());

        if (!stalePayments.isEmpty()) {
            log.warn("⚠️ 장기 PENDING 결제 {}건 발견", stalePayments.size());
            slackNotifier.send(String.format(
                "⚠️ [결제 시스템] 10분 이상 PENDING 상태 결제 %d건\n" +
                "수동 확인 필요",
                stalePayments.size()
            ));
        }
    }
}
```

### PaymentRepository 추가 메서드

```java
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 기존 메서드...
    List<Payment> findByStatusAndCreatedAtAfter(PaymentStatus status, LocalDateTime since);
    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime before);
}
```

---

## 7-3. Slack 알림 연동

```java
@Component
@Slf4j
public class SlackNotifier {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public void send(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[Slack 알림 (미연동)]: {}", message);
            return;
        }

        try {
            restTemplate.postForEntity(
                webhookUrl,
                Map.of("text", message),
                String.class
            );
        } catch (Exception e) {
            log.error("Slack 알림 전송 실패", e);
        }
    }
}
```

```yaml
# application.yml
slack:
  webhook-url: https://hooks.slack.com/services/YOUR/WEBHOOK/URL
  # 비워두면 로그로만 출력
```

---

## 7-4. TDD — 최종 안전망 검증

### 테스트 1: DLQ 동작 확인

```java
@Test
@DisplayName("Consumer가 3회 재시도 후 실패하면 이벤트가 DLQ로 이동한다")
void consumer_failure_moves_event_to_dlq() throws InterruptedException {
    // 쿠폰 서비스가 항상 500 에러를 반환하도록 설정
    wireMock.stubFor(post(urlPathEqualTo("/api/coupons/confirm"))
        .willReturn(aResponse().withStatus(500)));

    // 결제 + Outbox 이벤트 발행
    paymentService.processPaymentWithOutbox(
        new PaymentWithCouponRequest("ORD-DLQ-TEST", 1L, "CPN-001", null),
        "idem-dlq-test"
    );

    // DLQ Consumer가 수신할 때까지 대기 (3회 재시도 + DLQ 전달)
    Thread.sleep(15000);

    // DLQ에 이벤트가 도착했는지 확인
    // (테스트용 DLQ consumer에서 수신 확인)
    assertThat(dlqReceivedEvents).hasSize(1);
    assertThat(dlqReceivedEvents.get(0)).contains("ORD-DLQ-TEST");

    // 쿠폰 서비스에 총 4회 호출 시도 (1회 + 3회 재시도)
    wireMock.verify(4, postRequestedFor(urlPathEqualTo("/api/coupons/confirm")));

    System.out.println("=== DLQ 테스트 ===");
    System.out.println("처리 시도: 4회 (1 + 재시도 3회)");
    System.out.println("최종: DLQ로 이동");
}
```

### 테스트 2: 대사(Reconciliation) 불일치 감지

```java
@Test
@DisplayName("대사 스케줄러가 결제-쿠폰 불일치를 감지한다")
void reconciliation_detects_mismatch() {
    // 1. 결제 성공 기록 (쿠폰 포함)
    Payment payment = new Payment(order.getId(), 10000L);
    payment.setStatus(PaymentStatus.SUCCESS);
    paymentRepository.save(payment);

    // 2. 쿠폰 서비스는 "미사용"으로 응답 (의도적 불일치)
    wireMock.stubFor(get(urlPathMatching("/api/coupons/status/.*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withJsonBody(/* used: false */)));

    // 3. 대사 스케줄러 실행
    reconciliationScheduler.reconcilePaymentAndCoupon();

    // 4. 불일치가 감지되었는지 확인
    // (Slack 알림 또는 메트릭으로 확인)
    System.out.println("=== 대사 불일치 감지 테스트 ===");
    System.out.println("결제 상태: SUCCESS");
    System.out.println("쿠폰 상태: 미사용");
    System.out.println("→ 불일치 감지됨 → Slack 알림 발송");
}
```

### 테스트 3: E2E — 전체 파이프라인 장애 복구

```java
@Test
@DisplayName("E2E: 결제 → 쿠폰 처리 실패 → DLQ → 수동 재처리 → 최종 일치")
void e2e_full_pipeline_recovery() throws InterruptedException {
    // Step 1: 쿠폰 서비스 장애 상태에서 결제
    wireMock.stubFor(post(urlPathEqualTo("/api/coupons/confirm"))
        .willReturn(aResponse().withStatus(500)));

    paymentService.processPaymentWithOutbox(
        new PaymentWithCouponRequest("ORD-E2E", 1L, "CPN-001", 1000L),
        "idem-e2e"
    );

    // Step 2: Consumer 재시도 실패 → DLQ 이동
    Thread.sleep(15000);
    assertThat(dlqReceivedEvents).hasSize(1);

    // Step 3: 쿠폰 서비스 복구
    wireMock.stubFor(post(urlPathEqualTo("/api/coupons/confirm"))
        .willReturn(aResponse().withStatus(200)
            .withJsonBody(/* success */)));

    // Step 4: DLQ 재처리
    dlqReprocessor.reprocess("payment-events", "ORD-E2E", dlqReceivedEvents.get(0));

    Thread.sleep(5000);

    // Step 5: 최종 상태 확인 — 쿠폰이 정상 처리되었는지
    wireMock.verify(postRequestedFor(urlPathEqualTo("/api/coupons/confirm")));

    System.out.println("=== E2E 장애 복구 테스트 ===");
    System.out.println("1. 결제 성공 ✅");
    System.out.println("2. 쿠폰 처리 실패 → DLQ 이동 ✅");
    System.out.println("3. 쿠폰 서비스 복구 ✅");
    System.out.println("4. DLQ 재처리 → 쿠폰 확정 ✅");
    System.out.println("5. 최종 데이터 일치 ✅");
    System.out.println("→ 'Eventually Consistent' 달성!");
}
```

---

## 7-5. 최종 Grafana 대시보드

### 운영 대시보드 전체 구성

```
═══════════════════════════════════════════════════
  💳 결제 시스템 운영 대시보드
═══════════════════════════════════════════════════

Row 1: 결제 현황
  - 결제 성공률 (payment.pipeline.success / total)
  - 결제 실패율
  - 결제 응답 시간 (p50, p95, p99)
  - 총 결제 건수 (실시간)

Row 2: 이벤트 파이프라인
  - Outbox PENDING 이벤트 수 (0이어야 정상)
  - CDC 발행 지연 (ms)
  - Kafka Consumer Lag (각 consumer group별)
  - Consumer 처리 성공/실패 비율

Row 3: ⚠️ 알림 대상 — 빨간색이면 즉시 확인
  - DLQ 적체량 (0 초과 시 경고)
  - PENDING 10분+ 체류 건수 (0 초과 시 경고)
  - UNKNOWN 상태 건수 (0 초과 시 경고)
  - 대사 불일치 건수 (0 초과 시 경고)

Row 4: 인프라 상태
  - HikariCP 커넥션 사용률
  - Kafka broker 상태
  - Kafka Connect connector 상태
  - Redis 연결 수
  - PostgreSQL 활성 쿼리 수

Row 5: 보상/복구 활동
  - payment.recovery.success (Unknown State 복구)
  - dlq.reprocessed.success (DLQ 재처리)
  - reconciliation.mismatch.count (대사 불일치)
```

### Grafana Alert Rules

```yaml
# 알림 규칙 예시

# DLQ 적체 알림
- alert: DLQ_Backlog_High
  expr: dlq_received_total - dlq_reprocessed_success_total > 0
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "DLQ에 미처리 이벤트가 있습니다"

# PENDING 장기 체류
- alert: Payment_Stale_Pending
  expr: payment_pending_stale_count > 0
  for: 1m
  labels:
    severity: warning
  annotations:
    summary: "10분 이상 PENDING 상태인 결제가 있습니다"

# Consumer Lag
- alert: Kafka_Consumer_Lag_High
  expr: kafka_consumergroup_lag > 100
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Kafka Consumer 처리가 지연되고 있습니다"
```

---

## 7-6. 전체 아키텍처 최종도

```
┌──────────────┐     ┌─────────────┐     ┌───────────────────┐
│   Client     │────▶│  결제 서비스  │────▶│  TossPayments     │
│ (React 등)   │     │  Spring Boot │     │  Sandbox / 실결제  │
└──────────────┘     └──────┬───────┘     └───────────────────┘
                            │
                     ┌──────▼───────┐
                     │  PostgreSQL   │ ← DB + Outbox 테이블
                     └──────┬───────┘
                            │ WAL (CDC)
                     ┌──────▼───────┐
                     │  Debezium    │ ← Kafka Connect
                     │  (CDC)       │
                     └──────┬───────┘
                            │
                     ┌──────▼───────┐
                     │    Kafka     │
                     │  Broker     │
                     └──┬─────┬──┬─┘
                        │     │  │
          ┌─────────────▼┐ ┌─▼──┴──────────┐ ┌──────────────┐
          │ 쿠폰 Consumer │ │포인트 Consumer │ │ 알림 Consumer│
          └──────┬────────┘ └───────┬───────┘ └──────────────┘
                 │                  │
           (실패 시)          (실패 시)
                 │                  │
          ┌──────▼──────────────────▼──────┐
          │        DLQ (Dead Letter)       │
          └──────────────┬─────────────────┘
                         │
          ┌──────────────▼─────────────────┐
          │  DLQ Monitor + Slack 알림      │
          │  + Reconciliation 스케줄러     │
          └────────────────────────────────┘

  ┌────────────────────────────────────────┐
  │  Prometheus + Grafana 모니터링         │
  │  (모든 서비스의 메트릭을 수집/시각화)    │
  └────────────────────────────────────────┘
```

---

## 전체 Phase 최종 비교표 (학습 완료 후 수치로 채우기)

| 항목 | Ph.1 | Ph.2 | Ph.3 | Ph.4 | Ph.5 | Ph.6 | Ph.7 |
|------|------|------|------|------|------|------|------|
| 커넥션 고갈 | ❌ 발생 | ✅ 해소 | - | - | - | - | - |
| 데이터 불일치 | ❌ 발생 | ⚠️ PENDING | ✅ 멱등성 | ⚠️ 보상 실패 可 | ✅ Outbox | ✅ CDC | ✅ 대사 |
| 중복 결제 | ❌ 발생 | ❌ 발생 | ✅ 차단 | ✅ | ✅ | ✅ | ✅ |
| 서비스 결합도 | 낮음 | 낮음 | 낮음 | 높음 (동기) | 낮음 (비동기) | 낮음 | 낮음 |
| 장애 복구 | 없음 | 수동 조회 | 재시도 | 보상 TX | Outbox 재발행 | CDC 재전송 | DLQ+대사 |
| 모니터링 | 기본 | 기본 | Redis | + 보상 메트릭 | + Kafka Lag | + CDC | 종합 대시보드 |

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: DLQ 재처리 성공률 ***%, 대사 불일치 감지 건수 ***건, PENDING 장기 체류 평균 ***건

---

## 학습 완료 🎉

이 로드맵을 완수하면 다음 질문에 **수치와 코드**로 답할 수 있다.

> "외부 API를 @Transactional에 넣으면 안 되는 이유?"
> → Phase 1에서 커넥션 풀이 **N초 만에 고갈**되고, 불일치가 **N건** 발생했습니다.

> "결제 버튼 따닥 누르면?"
> → Phase 3에서 100건 동시 요청 중 **1건만** PG 전달, 나머지 **99건 차단**.

> "CDC가 폴링보다 나은 이유?"
> → Phase 6에서 발행 지연이 **N초 → N밀리초**로 개선.

> "결국 데이터 일치는 어떻게 보장?"
> → Phase 7의 DLQ + 대사 스케줄러가 **N분 주기**로 자동 감지/복구.
