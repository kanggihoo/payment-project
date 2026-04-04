# Phase 6. CDC — Kafka Connect + Debezium

> "Outbox 테이블을 폴링하지 말고, DB의 WAL(트랜잭션 로그)을 실시간으로 읽자."

---

## 이 Phase의 목표

- Phase 5의 폴링 릴레이어를 **Debezium CDC**로 교체하여 실시간 이벤트 발행을 달성한다
- CDC의 동작 원리(WAL 기반)를 이해하고, Connector 장애 시 동작을 확인한다
- 컨슈머 측 중복 이벤트, 이벤트 순서 역전 문제를 발견한다

---

## 이전 Phase의 문제를 어떻게 해결하는가

```
Phase 5:  App → Outbox INSERT → [폴링 릴레이어 5초 주기] → Kafka
  문제: 5초 지연, DB SELECT 부하, 릴레이어 장애 시 적체

Phase 6:  App → Outbox INSERT → [Debezium CDC] → Kafka
  장점: WAL 실시간 읽기, 밀리초 지연, DB 부하 없음, 폴링 프로세스 제거
```

---

## 6-1. 인프라 추가 — Kafka Connect + Debezium

### Docker Compose

```yaml
# docker-compose.app.yml에 추가
  kafka-connect:
    image: debezium/connect:2.5
    ports:
      - "8083:8083"
    environment:
      BOOTSTRAP_SERVERS: kafka:29092
      GROUP_ID: connect-cluster
      CONFIG_STORAGE_TOPIC: connect-configs
      OFFSET_STORAGE_TOPIC: connect-offsets
      STATUS_STORAGE_TOPIC: connect-status
      CONFIG_STORAGE_REPLICATION_FACTOR: 1
      OFFSET_STORAGE_REPLICATION_FACTOR: 1
      STATUS_STORAGE_REPLICATION_FACTOR: 1
    depends_on:
      - kafka
      - postgres
```

### PostgreSQL WAL 설정

```sql
-- PostgreSQL에서 논리적 복제(Logical Replication)를 활성화해야 한다.
-- postgresql.conf 또는 Docker 환경변수로 설정:
-- wal_level = logical
```

```yaml
# docker-compose.app.yml postgres 서비스에 추가
  postgres:
    image: postgres:17-alpine
    command: >
      postgres
      -c wal_level=logical
      -c max_replication_slots=4
      -c max_wal_senders=4
    # ... 기존 설정 유지
```

---

## 6-2. Debezium Connector 등록

### Outbox Event Router 사용

Debezium의 `outbox.event.router` SMT(Single Message Transform)를 사용하면, Outbox 테이블 INSERT를 자동으로 토픽에 라우팅한다.

```bash
# Kafka Connect REST API로 connector 등록
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "payment-outbox-connector",
    "config": {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "database.hostname": "postgres",
        "database.port": "5432",
        "database.user": "user",
        "database.password": "password",
        "database.dbname": "payment",
        "database.server.name": "payment-db",
        "topic.prefix": "payment-db",

        "table.include.list": "public.outbox_event",
        "slot.name": "outbox_slot",
        "plugin.name": "pgoutput",

        "transforms": "outbox",
        "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
        "transforms.outbox.table.field.event.id": "id",
        "transforms.outbox.table.field.event.key": "aggregate_id",
        "transforms.outbox.table.field.event.type": "event_type",
        "transforms.outbox.table.field.event.payload": "payload",
        "transforms.outbox.route.by.field": "aggregate_type",
        "transforms.outbox.route.topic.replacement": "payment-events",

        "tombstones.on.delete": "false",
        "key.converter": "org.apache.kafka.connect.storage.StringConverter",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "false"
    }
}'
```

### Connector 상태 확인

```bash
# Connector 상태 확인
curl http://localhost:8083/connectors/payment-outbox-connector/status | jq

# 등록된 connector 목록
curl http://localhost:8083/connectors | jq

# Connector 재시작
curl -X POST http://localhost:8083/connectors/payment-outbox-connector/restart
```

---

## 6-3. 폴링 릴레이어 제거

Phase 5의 `OutboxPollingRelay`는 더 이상 필요 없다. Debezium이 WAL을 읽어 자동으로 발행한다.

```java
// Phase 5의 OutboxPollingRelay를 비활성화하거나 제거
// @Component  ← 주석 처리
// public class OutboxPollingRelay { ... }

// 대신, Outbox 테이블에 INSERT만 하면 Debezium이 자동으로 Kafka에 발행한다.
// Service 코드(Phase 5의 processPaymentWithOutbox)는 변경 없음!
```

> **핵심 포인트**: 애플리케이션 코드 변경 없이, 인프라(Debezium) 추가만으로 폴링 → CDC 전환 완료.

---

## 6-4. Consumer 멱등성 강화

CDC는 **at-least-once** 전달을 보장한다. Connector 재시작 시 이미 발행한 이벤트를 다시 보낼 수 있으므로, Consumer 측 멱등성이 필수다.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponEventConsumer {

    private final CouponClient couponClient;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String PROCESSED_KEY_PREFIX = "consumer:coupon:processed:";

    @KafkaListener(topics = "payment-events", groupId = "coupon-service")
    public void handlePaymentCompleted(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {

        String eventId = key;  // aggregate_id (주문번호)

        // 멱등성: 이미 처리한 이벤트인지 확인
        Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent(PROCESSED_KEY_PREFIX + eventId, "done", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNew)) {
            log.info("중복 이벤트 스킵: {}", eventId);
            meterRegistry.counter("consumer.coupon.duplicate_skipped").increment();
            return;
        }

        try {
            PaymentCompletedEvent event = new ObjectMapper()
                .readValue(payload, PaymentCompletedEvent.class);

            if (event.couponId() != null) {
                couponClient.confirm(event.couponId());
                meterRegistry.counter("consumer.coupon.success").increment();
            }
        } catch (Exception e) {
            // 처리 실패 시 Redis 키 제거 → 재시도 허용
            redisTemplate.delete(PROCESSED_KEY_PREFIX + eventId);
            meterRegistry.counter("consumer.coupon.failed").increment();
            throw new RuntimeException(e);
        }
    }
}
```

---

## 6-5. TDD — CDC 동작 검증

### 테스트 1: CDC가 Outbox INSERT를 실시간으로 Kafka에 발행하는지

```java
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"payment-events"})
class CdcOutboxIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private KafkaConsumer<String, String> testConsumer;

    @Test
    @DisplayName("Outbox에 이벤트를 INSERT하면 CDC가 Kafka에 발행한다")
    void outbox_insert_triggers_cdc_publish() throws InterruptedException {
        // 결제 실행 → Outbox에 INSERT
        paymentService.processPaymentWithOutbox(
            new PaymentWithCouponRequest("ORD-CDC-TEST", 1L, "CPN-001", 1000L),
            "idem-cdc-test"
        );

        // Kafka에서 이벤트 수신 확인 (최대 10초 대기)
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));

        assertThat(records.count()).isGreaterThan(0);

        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.key()).isEqualTo("ORD-CDC-TEST");

        System.out.println("=== CDC 발행 테스트 ===");
        System.out.println("Outbox INSERT → Kafka 도착 지연: " +
            (System.currentTimeMillis() - record.timestamp()) + "ms");
    }
}
```

### 테스트 2: Connector 재시작 시 중복 이벤트 확인

```java
@Test
@DisplayName("Connector 재시작 시 중복 이벤트가 발생하지만 Consumer 멱등성으로 방어된다")
void connector_restart_causes_duplicate_but_consumer_handles_it() {
    // 1. 이벤트 발행 후 정상 처리
    paymentService.processPaymentWithOutbox(
        new PaymentWithCouponRequest("ORD-DUP", 1L, "CPN-001", 1000L),
        "idem-dup-test"
    );

    // 2. 같은 이벤트를 Kafka에 수동으로 재발행 (Connector 재시작 시뮬레이션)
    kafkaTemplate.send("payment-events", "ORD-DUP", duplicatePayload);

    // 3. Consumer가 두 번째 이벤트를 스킵하는지 확인
    Thread.sleep(5000);

    // 쿠폰 confirm은 1번만 호출되어야 한다
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/coupons/confirm")));
}
```

### 테스트 3: 이벤트 순서 역전 시나리오

```java
@Test
@DisplayName("같은 주문의 결제 성공-환불 이벤트가 순서 역전되면 문제가 발생한다")
void event_ordering_issue() {
    // Kafka 파티션 키가 같으면 순서가 보장되지만,
    // 다른 파티션에 들어가면 순서가 역전될 수 있다.

    // 1. 결제 성공 이벤트 발행
    kafkaTemplate.send("payment-events", "ORD-ORDER-TEST",
        "{\"eventType\":\"PAYMENT_COMPLETED\",\"orderNumber\":\"ORD-ORDER-TEST\"}");

    // 2. 환불 이벤트 발행 (같은 파티션 키 → 순서 보장)
    kafkaTemplate.send("payment-events", "ORD-ORDER-TEST",
        "{\"eventType\":\"PAYMENT_REFUNDED\",\"orderNumber\":\"ORD-ORDER-TEST\"}");

    // 파티션 키가 같으므로 순서가 보장되어야 한다
    // → "결제 성공"이 먼저 처리되고, "환불"이 그 다음에 처리

    System.out.println("=== 이벤트 순서 보장 테스트 ===");
    System.out.println("파티션 키: ORD-ORDER-TEST (동일)");
    System.out.println("→ 같은 파티션에 할당되어 순서가 보장됨");
    System.out.println("→ 다른 키를 쓰면 순서 역전 가능!");
}
```

---

## 6-6. 폴링 vs CDC 성능 비교 테스트

```java
@Test
@DisplayName("폴링(5초 주기) vs CDC 발행 지연 비교")
void polling_vs_cdc_latency_comparison() {
    // 100건의 결제를 실행하고 Kafka 도착까지의 지연을 측정

    List<Long> cdcLatencies = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
        long start = System.currentTimeMillis();

        paymentService.processPaymentWithOutbox(
            new PaymentWithCouponRequest("ORD-LATENCY-" + i, 1L, null, null),
            "idem-latency-" + i
        );

        // Kafka에서 수신 대기
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        long latency = System.currentTimeMillis() - start;
        cdcLatencies.add(latency);
    }

    double avgLatency = cdcLatencies.stream().mapToLong(l -> l).average().orElse(0);
    long maxLatency = cdcLatencies.stream().mapToLong(l -> l).max().orElse(0);

    System.out.println("=== 폴링 vs CDC 지연 비교 ===");
    System.out.println("CDC 평균 지연: " + avgLatency + "ms");
    System.out.println("CDC 최대 지연: " + maxLatency + "ms");
    System.out.println("폴링 평균 지연: ~2500ms (5초 주기의 평균)");
    System.out.println("→ CDC가 약 " + (2500 / avgLatency) + "배 빠름");
}
```

---

## 6-7. Grafana 모니터링 패널

```
Row 1: CDC 발행 성능
  - Outbox INSERT 시각 → Kafka 도착 시각 차이 (ms)
  - 폴링 대비 지연 개선율

Row 2: Connector 상태
  - Kafka Connect REST API 상태 (/connectors/status)
  - kafka_consumer_lag (Connect 내부 consumer)
  - Replication slot lag (PostgreSQL)

Row 3: Consumer 처리
  - consumer.coupon.duplicate_skipped  (중복 이벤트 스킵 건수)
  - consumer.coupon.success / failed
  - consumer.point.success / failed
```

### PromQL

```promql
# Kafka consumer lag (Connect connector)
kafka_consumergroup_lag{consumergroup="connect-cluster"}

# PostgreSQL replication slot lag
pg_replication_slots_pg_wal_lsn_diff
```

---

## Phase 5와의 비교 측정표

| 항목 | Phase 5 (폴링) | Phase 6 (CDC) |
|------|---------------|---------------|
| 발행 지연 | 평균 ~2.5초 (5초 주기) | 밀리초 단위 |
| DB 부하 | SELECT 쿼리 주기적 실행 | WAL 읽기 (추가 부하 거의 없음) |
| 애플리케이션 코드 변경 | 폴링 릴레이어 필요 | 코드 변경 없음 (인프라만 추가) |
| 중복 발행 | 폴링 중복 가능 | Connector 재시작 시 중복 가능 |
| 인프라 복잡도 | 낮음 | Kafka Connect + Debezium 추가 |

---

## 회고

- **예상과 달랐던 점**:
- **가장 어려웠던 점**:
- **실무에 적용한다면**:
- **핵심 수치 요약**: CDC 발행 지연 평균 ***ms (폴링 대비 ***배 개선), Connector 재시작 시 중복 이벤트 ***건, DB 부하 ***% 감소

---

## ❓ 남은 문제 → Phase 7로

> "이벤트 기반으로 전환했지만, Consumer가 계속 실패하는 이벤트는 어떻게 처리하는가? 시간이 지나면서 시스템 간 데이터가 어긋나면 어떻게 감지하는가?"

→ [[phase7-safety-net]]
