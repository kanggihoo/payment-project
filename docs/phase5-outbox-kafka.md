# Phase 5. Outbox 패턴 + Kafka 비동기 이벤트 전환

> "DB 커밋과 이벤트 발행을 원자적으로 보장하면서, 서비스 간 결합을 끊는다."

---

## 이 Phase의 목표

- Phase 4의 **동기 호출 체인**(결제 → 쿠폰 → 포인트)을 **비동기 이벤트** 기반으로 전환한다
- **Dual Write 문제**(DB 저장 + Kafka 발행 중 하나만 실패)를 Outbox 패턴으로 해결한다
- 폴링 기반 릴레이어의 한계(지연, 중복 발행)를 직접 확인한다

---

## 이전 Phase의 문제를 어떻게 해결하는가

```
Phase 4 (동기 체인):
  결제 서비스 → HTTP → 쿠폰 서비스 → HTTP → 포인트 서비스
  문제: 하나만 장애 → 전체 실패, 보상 실패 → 영구 불일치

Phase 5 (비동기 이벤트):
  결제 서비스 → DB + Outbox 저장 (한 트랜잭션)
           → [릴레이어] → Kafka
                        → 쿠폰 Consumer (독립 처리)
                        → 포인트 Consumer (독립 처리)
                        → 알림 Consumer (새로 추가)
  장점: 결제 서비스는 PG만 처리하고 끝. 나머지는 독립적으로 처리.
```

---

## 5-1. 인프라 추가 — Kafka

### Docker Compose

```yaml
# docker-compose.app.yml에 추가
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    depends_on:
      - zookeeper

  kafka-exporter:
    image: danielqsj/kafka-exporter
    ports:
      - "9308:9308"
    command: --kafka.server=kafka:29092
    depends_on:
      - kafka
```

### Prometheus에 Kafka 메트릭 추가

```yaml
# prometheus.yml에 추가
  - job_name: "kafka"
    static_configs:
      - targets: ["kafka-exporter:9308"]
```

### build.gradle 추가

```gradle
implementation 'org.springframework.kafka:spring-kafka'
testImplementation 'org.springframework.kafka:spring-kafka-test'
testImplementation 'org.testcontainers:kafka'
```

---

## 5-2. Outbox 테이블 설계

```sql
-- Outbox 테이블: DB 트랜잭션과 함께 이벤트를 원자적으로 저장
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,     -- 'payment'
    aggregate_id    VARCHAR(100) NOT NULL,     -- 주문번호
    event_type      VARCHAR(100) NOT NULL,     -- 'PAYMENT_COMPLETED', 'PAYMENT_FAILED'
    payload         JSONB NOT NULL,            -- 이벤트 데이터
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING: 미발행
    -- PUBLISHED: Kafka에 발행 완료
    -- FAILED: 발행 실패
    created_at      TIMESTAMP DEFAULT NOW(),
    published_at    TIMESTAMP
);

CREATE INDEX idx_outbox_status ON outbox_event(status) WHERE status = 'PENDING';
```

---

## 5-3. Outbox Entity + Repository

```java
@Entity
@Table(name = "outbox_event")
@Getter @Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime publishedAt;

    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }
}

public enum OutboxStatus {
    PENDING, PUBLISHED, FAILED
}

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
    long countByStatus(OutboxStatus status);
}
```

---

## 5-4. Service 변경 — Outbox 이벤트 저장

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentInternalService internalService;
    private final PgClient pgClient;
    private final RedisIdempotencyStore idempotencyStore;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Phase 5: 결제 성공 후 Outbox에 이벤트를 DB 트랜잭션과 함께 저장한다.
     * 쿠폰/포인트/알림은 Kafka Consumer가 비동기로 처리한다.
     */
    @Transactional
    public PaymentResult processPaymentWithOutbox(
            PaymentWithCouponRequest request, String idempotencyKey) {

        // 멱등성 검증
        if (!idempotencyStore.tryAcquire(idempotencyKey)) {
            throw new DuplicatePaymentException("중복 요청");
        }

        try {
            // TX1: PENDING
            PaymentPendingResult pending = internalService.createPendingPayment(
                request.orderNumber()
            );

            // 외부 호출: PG 승인 (결제만 동기로 처리)
            PgApproveResponse pgResponse = pgClient.approve(
                pending.orderNumber(), pending.amount()
            );

            // TX2: SUCCESS + Outbox 이벤트 저장 (같은 트랜잭션!)
            internalService.completePayment(pending.paymentId(), pgResponse.paymentKey());

            // Outbox에 이벤트 저장 — DB 트랜잭션과 원자적으로 커밋
            PaymentCompletedEvent event = new PaymentCompletedEvent(
                request.orderNumber(),
                pgResponse.paymentKey(),
                request.userId(),
                request.couponId(),
                request.pointAmount(),
                pending.amount()
            );

            outboxEventRepository.save(new OutboxEvent(
                "payment",
                request.orderNumber(),
                "PAYMENT_COMPLETED",
                objectMapper.writeValueAsString(event)
            ));

            meterRegistry.counter("payment.outbox.saved").increment();

            return new PaymentResult(request.orderNumber(), "SUCCESS", pgResponse.paymentKey());

        } catch (Exception e) {
            idempotencyStore.release(idempotencyKey);
            throw e;
        }
    }
}

public record PaymentCompletedEvent(
    String orderNumber,
    String paymentKey,
    Long userId,
    String couponId,
    Long pointAmount,
    Long totalAmount
) {}
```

---

## 5-5. Outbox 폴링 릴레이어

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollingRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * 주기적으로 PENDING 상태의 Outbox 이벤트를 Kafka에 발행한다.
     *
     * ⚠️ 이 폴링 방식의 한계:
     * - 폴링 주기(5초)만큼 이벤트 발행이 지연된다
     * - DB에 SELECT 쿼리 부하가 발생한다
     * - 릴레이어 장애 시 이벤트가 쌓인다
     * → Phase 6에서 CDC(Debezium)로 교체하여 해결
     */
    @Scheduled(fixedDelay = 5000)  // 5초 주기 폴링
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
            .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent event : pendingEvents) {
            try {
                // Kafka 발행 — 파티션 키는 aggregateId(주문번호)로 순서 보장
                kafkaTemplate.send(
                    "payment-events",       // 토픽
                    event.getAggregateId(), // 파티션 키 (같은 주문은 같은 파티션)
                    event.getPayload()
                ).get();  // 동기 전송 (발행 확인)

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                meterRegistry.counter("outbox.published").increment();

                log.info("Outbox 이벤트 발행: {} / {}", event.getEventType(), event.getAggregateId());

            } catch (Exception e) {
                log.error("Outbox 이벤트 발행 실패: {}", event.getId(), e);
                event.setStatus(OutboxStatus.FAILED);
                meterRegistry.counter("outbox.publish.failed").increment();
            }
        }
    }
}
```

---

## 5-6. Kafka Consumer — 쿠폰, 포인트, 알림

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponEventConsumer {

    private final CouponClient couponClient;
    private final MeterRegistry meterRegistry;

    @KafkaListener(topics = "payment-events", groupId = "coupon-service")
    public void handlePaymentCompleted(String payload) {
        try {
            PaymentCompletedEvent event = new ObjectMapper()
                .readValue(payload, PaymentCompletedEvent.class);

            if (event.couponId() != null) {
                couponClient.confirm(event.couponId());
                log.info("쿠폰 확정 완료: {}", event.couponId());
                meterRegistry.counter("consumer.coupon.success").increment();
            }
        } catch (Exception e) {
            log.error("쿠폰 처리 실패", e);
            meterRegistry.counter("consumer.coupon.failed").increment();
            throw new RuntimeException(e);  // 재시도 트리거
        }
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class PointEventConsumer {

    private final PointClient pointClient;
    private final MeterRegistry meterRegistry;

    @KafkaListener(topics = "payment-events", groupId = "point-service")
    public void handlePaymentCompleted(String payload) {
        try {
            PaymentCompletedEvent event = new ObjectMapper()
                .readValue(payload, PaymentCompletedEvent.class);

            if (event.pointAmount() != null && event.pointAmount() > 0) {
                pointClient.deduct(event.userId(), event.pointAmount());
                log.info("포인트 차감 완료: {}원", event.pointAmount());
                meterRegistry.counter("consumer.point.success").increment();
            }
        } catch (Exception e) {
            log.error("포인트 처리 실패", e);
            meterRegistry.counter("consumer.point.failed").increment();
            throw new RuntimeException(e);
        }
    }
}

@Component
@Slf4j
public class NotificationEventConsumer {

    @KafkaListener(topics = "payment-events", groupId = "notification-service")
    public void handlePaymentCompleted(String payload) {
        try {
            PaymentCompletedEvent event = new ObjectMapper()
                .readValue(payload, PaymentCompletedEvent.class);

            // 알림 발송 (이메일, 푸시 등)
            log.info("📧 결제 완료 알림 발송: 주문 {}, 금액 {}원",
                event.orderNumber(), event.totalAmount());
        } catch (Exception e) {
            log.error("알림 발송 실패", e);
        }
    }
}
```

### Kafka 설정

```yaml
# application.yml 추가
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all  # 모든 복제본에 기록 후 확인
    consumer:
      group-id: payment-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false  # 수동 커밋 (exactly-once에 가까운 처리)
    listener:
      ack-mode: record  # 레코드 단위 커밋
```

---

## 5-7. TDD — Outbox + 비동기 처리 검증

### 테스트 1: Outbox에 이벤트가 DB와 원자적으로 저장되는지

```java
@Test
@DisplayName("결제 성공 시 Outbox 이벤트가 같은 트랜잭션에 저장된다")
void outboxEvent_saved_atomically_with_payment() {
    PaymentResult result = paymentService.processPaymentWithOutbox(
        new PaymentWithCouponRequest("ORD-OUTBOX", 1L, "CPN-001", 1000L),
        "idem-outbox-test"
    );

    assertThat(result.status()).isEqualTo("SUCCESS");

    // Outbox에 PENDING 이벤트가 정확히 1건 있어야 한다
    List<OutboxEvent> events = outboxEventRepository
        .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("PAYMENT_COMPLETED");
    assertThat(events.get(0).getAggregateId()).isEqualTo("ORD-OUTBOX");
}
```

### 테스트 2: Kafka 장애 시 Outbox에 이벤트가 쌓이는지

```java
@Test
@DisplayName("Kafka가 장애 상태일 때 Outbox에 미발행 이벤트가 적체된다")
void kafka_down_causes_outbox_backlog() {
    // Kafka 연결 불가 상태에서 결제 10건 실행
    for (int i = 0; i < 10; i++) {
        paymentService.processPaymentWithOutbox(
            new PaymentWithCouponRequest("ORD-KAFKA-" + i, 1L, null, null),
            "idem-kafka-" + i
        );
    }

    // Outbox PENDING 이벤트가 10건 쌓여있어야 한다
    long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
    assertThat(pendingCount).isEqualTo(10);

    System.out.println("=== Kafka 장애 시 Outbox 적체 ===");
    System.out.println("PENDING 이벤트: " + pendingCount + "건");
    System.out.println("→ Kafka가 복구되면 릴레이어가 순차적으로 발행한다");
}
```

---

## 5-8. Grafana 모니터링 패널

```
Row 1: Outbox 상태
  - outbox.pending.count  (게이지: 현재 미발행 이벤트 수)
  - outbox.published      (카운터: 발행 완료)
  - outbox.publish.failed (카운터: 발행 실패)

Row 2: Kafka Consumer 상태
  - kafka_consumer_lag     (컨슈머 처리 지연)
  - consumer.coupon.success / failed
  - consumer.point.success / failed

Row 3: 이벤트 흐름 비교
  - Phase 4 동기 파이프라인 응답 시간 vs Phase 5 결제만의 응답 시간
```

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: Outbox 폴링 지연 평균 ***초, 결제 응답 시간 Phase 4 대비 ***% 감소, Kafka 장애 시 미발행 적체 ***건

---

## ❓ 남은 문제 → Phase 6로

> "폴링은 비효율적이다. 5초 주기 SELECT가 DB에 부하를 주고, 이벤트 발행이 최대 5초 지연된다. DB 변경을 실시간으로 캡처할 방법이 없을까?"

→ [[phase6-cdc-debezium]]
