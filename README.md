# 결제 시스템의 점진적 고도화 로드맵

> **핵심 철학:** "가장 단순한 구현"에서 시작하여, 각 Phase에서 **발생하는 문제를 직접 눈으로 확인**하고, 그 문제를 해결하기 위한 도구를 **하나씩** 도입한다. 해결책이 만드는 **새로운 문제**가 다음 Phase의 동기가 된다.

---

## 🛠 기술 스택 (Tech Stack)

### Backend

- **Framework**: Spring Boot 4.x
- **Build Tool**: Gradle 8.14.x
- **Language**: Java 21 (LTS)
- **Libraries**: Spring Data JPA, Spring Data Redis, Redisson (분산 락), Micrometer Prometheus
- **Testing**: JUnit 5, Testcontainers, Spring Boot Test, AssertJ, WireMock

### External API

- **Phase 1~2**: **WireMock** (가상 PG — 지연, 타임아웃, 오류를 자유롭게 모킹)
- **Phase 3~**: **TossPayments Sandbox** (실제 PG 연동 — 실제 결제 흐름, 멱등성 키 동작, 환불 API 확인)

> WireMock → TossPayments 교체 이유: WireMock으로 "중복 방지가 필요하다"는 것을 증명한 후, 실제 PG의 Idempotency-Key 동작을 검증하는 것이 자연스러운 흐름.

### Infrastructure (Docker Compose)

- **RDBMS**: PostgreSQL 17-alpine
- **Messaging**: Apache Kafka (Phase 5~)
- **CDC**: Kafka Connect + Debezium (Phase 6~)
- **Cache / Distributed Lock**: Redis 7.2-alpine (Phase 3~)
- **Mock PG Server**: WireMock (Phase 1~2)
- **Monitoring**: Prometheus (latest), Grafana (latest)
- **Load Testing**: k6 (latest, Docker 이미지 사용)

### Monitoring & Observability

> 동시성 프로젝트와 동일하게 **모든 Phase에서 Prometheus + Grafana를 상시 가동**.
> 각 Phase의 문제가 Grafana 대시보드에서 수치로 보여야 "인사이트"가 완성된다.

- **Spring Metrics**: Micrometer → Prometheus → Grafana
- **Kafka Metrics**: JMX Exporter 또는 kafka-exporter → Prometheus
- **DB Metrics**: postgres-exporter → Prometheus
- **k6 결과**: `--out experimental-prometheus-rw` → Prometheus → Grafana

#### Phase별 핵심 Grafana 패널

| Phase | 핵심 지표                                   | 무엇을 보는가                           |
| ----- | ------------------------------------------- | --------------------------------------- |
| 1     | `hikaricp_connections_active`               | 커넥션 풀이 언제 고갈되는가             |
| 2     | 커스텀 메트릭: `payment.state.pending` 개수 | Unknown State가 얼마나 쌓이는가         |
| 3     | 커스텀 메트릭: `payment.duplicate.blocked`  | 중복 요청 중 몇 건이 막혔는가           |
| 4     | HTTP 에러율, 보상 성공/실패 비율            | 다단계 실패 시 보상이 얼마나 동작하는가 |
| 5     | Outbox 미발행 이벤트 수, Kafka Consumer Lag | 이벤트가 얼마나 지연되고 쌓이는가       |
| 6     | CDC 발행 지연 (ms), Topic offset 차이       | 폴링 대비 CDC의 실시간성 차이           |
| 7     | DLQ 적체량, Reconciliation 불일치 건수      | 최종 일치까지 얼마나 걸리는가           |

### Testing & Verification

- **Unit / Integration Test**: JUnit 5 + Testcontainers (TDD)
- **동시성 정합성 테스트**: CountDownLatch + ExecutorService
- **외부 API 모킹**: WireMock (지연, 타임아웃, 오류 시뮬레이션)
- **부하 테스트**: k6 (시나리오별 VU, 스파이크, 지속 부하)
- **E2E 통합 테스트**: Phase별 전체 흐름 검증

---

## 🔗 문제 → 해결 → 새로운 문제 체인

```
Phase 1: 나이브한 구현
  └─ 발견된 문제: 커넥션 풀 고갈 + 데이터 불일치 (돈은 빠졌는데 DB 롤백)
       │
Phase 2: 트랜잭션 분리 + 상태 머신
  └─ 해결: 커넥션 고갈, 부분적 불일치
  └─ 발견된 문제: Unknown State (타임아웃 — 돈이 빠졌는지 안 빠졌는지 모름)
       │
Phase 3: 멱등성(Idempotency) 보장 + TossPayments Sandbox 연동
  └─ 해결: 중복 결제, 재시도 안전성
  └─ 발견된 문제: "결제 후 쿠폰 차감" 같은 다단계 처리에서 중간 실패 시 정합성 깨짐
       │
Phase 4: 보상 트랜잭션 (Compensating Transaction)
  └─ 해결: 다단계 실패 시 동기적 롤백 (환불 API 호출)
  └─ 발견된 문제: 보상 자체가 실패하면? + 동기 호출의 강결합/연쇄 장애 위험
       │
Phase 5: Outbox 패턴 + Kafka 비동기 이벤트 전환
  └─ 해결: DB 트랜잭션과 이벤트 발행의 원자성 보장, 서비스 간 느슨한 결합
  └─ 발견된 문제: Outbox 테이블 폴링의 비효율성 + 지연 + 중복 발행
       │
Phase 6: CDC (Kafka Connect / Debezium)
  └─ 해결: 폴링 없이 DB WAL을 실시간으로 Kafka에 발행
  └─ 발견된 문제: 컨슈머 장애, 중복 이벤트, 이벤트 순서 역전
       │
Phase 7: 최후의 안전망 — DLQ + 대사(Reconciliation) + 모니터링
  └─ 해결: 모든 실패 케이스에 대한 방어 + 운영 가시성 확보
  └─ 최종 상태: "어디서 장애가 나도 데이터는 결국 일치한다"
```

---

## 비교 시나리오 (전 Phase 공통 도메인)

```
온라인 쇼핑몰 결제 — 상품 주문 + 쿠폰 적용 + 포인트 사용

POST /api/payments
  → 주문 생성
  → (외부) PG사 결제 승인
  → 쿠폰 차감        ← Phase 4부터 추가
  → 포인트 차감      ← Phase 4부터 추가
  → 알림 발송        ← Phase 5부터 추가
```

> Phase 1~3은 결제 단일 흐름만 다룬다. Phase 4부터 쿠폰/포인트가 추가되고, Phase 5부터 알림이 추가된다. 복잡도가 단계적으로 증가한다.

---

## Phase 1. 베이스라인 — 나이브한 결제

> "외부 API 호출을 `@Transactional` 안에 넣으면 어떤 참사가 발생하는가?"

### 구현 대상

- `@Transactional` 하나로 주문 생성 → PG사 결제 요청 → 결제 내역 저장을 모두 처리
- PG사는 **WireMock**으로 모킹 (지연, 오류를 자유롭게 시뮬레이션)

### 테스트로 유발할 문제

| #   | 시나리오           | 방법                                          | 관찰할 현상                                        |
| --- | ------------------ | --------------------------------------------- | -------------------------------------------------- |
| 1   | **커넥션 풀 고갈** | WireMock 응답 10초 지연 + k6 50VU             | DB 커넥션을 쥔 채 외부 API 대기 → 시스템 전체 먹통 |
| 2   | **데이터 불일치**  | PG 승인 성공 후 DB 저장 시점에 예외 강제 발생 | PG사에서는 돈 빠짐 + DB는 롤백 → **고객 돈 증발**  |

### 모니터링으로 확인하는 것

- `hikaricp_connections_active` → 풀 고갈 시점 Grafana에서 시각 확인
- k6 에러율 급등 시점 vs 커넥션 풀 고갈 시점 일치 여부
- 불일치 발생 건수를 커스텀 메트릭으로 수집

### 이 Phase에서 얻는 인사이트

- 외부 API 호출은 **절대** DB 트랜잭션 안에 넣으면 안 된다
- "PG에서는 성공인데 우리 DB에서는 실패" — 이것이 결제 시스템의 **근본 문제**

### 측정 지표 (회고용)

- HikariCP 커넥션 고갈까지 걸린 시간 (초)
- 불일치 발생 건수 (N건 / 총 요청의 N%)
- WireMock 지연 적용 전후 시스템 응답 시간 차이

### ❓ 남은 문제 → Phase 2로

> "트랜잭션을 분리하면 해결될까? 그런데 분리한 후 외부 API 호출 도중 타임아웃이 나면?"

---

## Phase 2. 트랜잭션 분리 + Unknown State 핸들링

> "응답을 받지 못했다면, 결제는 성공한 것인가 실패한 것인가?"

### 이전 Phase의 문제를 어떻게 해결하는가

- **TX 분리**: 외부 API 호출을 트랜잭션 밖으로 꺼냄
  - TX1: 주문을 `PENDING` 상태로 저장 → 커밋 (커넥션 반납)
  - (트랜잭션 없이) PG사 API 호출
  - TX2: 결과에 따라 `SUCCESS` 또는 `FAILED`로 업데이트

### 테스트로 유발할 문제

| #   | 시나리오         | 방법                                                | 관찰할 현상                                                |
| --- | ---------------- | --------------------------------------------------- | ---------------------------------------------------------- |
| 1   | **Read Timeout** | PG 요청은 정상 전달, 응답 중 네트워크 단절 모킹     | DB는 `PENDING`인데 실제 결제 여부 불명 (**Unknown State**) |
| 2   | **앱 크래시**    | PG 승인 성공 직후, TX2 실행 전에 프로세스 강제 종료 | `PENDING` 상태로 영원히 잔류                               |

### 해결 — 복구 메커니즘

- PG사 **단건 조회 API**(Polling)로 실제 승인 여부를 확인
- 사용자에게는 "결제 상태 확인 중" 반환 → 재조회 후 상태 확정

### 모니터링으로 확인하는 것

- `payment.state.pending` 커스텀 게이지 메트릭 → PENDING 상태 건수 추이
- 타임아웃 발생 비율 vs 정상 응답 비율

### 이 Phase에서 얻는 인사이트

- 상태 머신(`PENDING → SUCCESS/FAILED`)의 필요성
- "응답 없음 ≠ 실패"라는 분산 시스템의 핵심 원칙

### 측정 지표 (회고용)

- `PENDING` 상태 평균 체류 시간
- Unknown State 발생 빈도 (타임아웃 비율)
- 조회 API polling을 통한 복구 성공률

### ❓ 남은 문제 → Phase 3로

> "타임아웃으로 재시도했더니 결제가 두 번 됐다. 사용자가 버튼을 따닥 눌렀더니 두 번 결제됐다."

---

## Phase 3. 멱등성(Idempotency) 보장 + TossPayments Sandbox 연동

> "같은 결제 요청을 100번 보내도 딱 1번만 처리되어야 한다."

### TossPayments Sandbox로 교체하는 이유

WireMock으로 **"중복이 발생한다"는 문제를 먼저 증명**하고, 이 Phase에서 실제 Sandbox로 교체하여:

- TossPayments의 실제 Idempotency-Key 동작 확인
- 실제 환불(취소) API 응답 구조 확인
- 이후 Phase의 보상 트랜잭션 구현 준비

### 이전 Phase의 문제를 어떻게 해결하는가

- 클라이언트가 `Idempotency-Key` (주문 ID 기반 UUID) 발급
- 서버는 이 키로 **"이미 처리 중이거나 완료된 결제"인지 검증** 후 중복 차단
- 검증 수단: DB `UNIQUE INDEX` 또는 `Redis SET NX` (동시성 프로젝트 지식 재활용)

### 테스트로 유발할 문제

| #   | 시나리오                   | 방법                                                               | 검증 기준                                      |
| --- | -------------------------- | ------------------------------------------------------------------ | ---------------------------------------------- |
| 1   | **동시 중복 요청**         | 동일 `orderId`로 100건 동시 발사 (Testcontainers + CountDownLatch) | 정확히 1건만 PG로 전달, 99건 거절              |
| 2   | **순차 재시도**            | 타임아웃 후 동일 키로 재시도                                       | 이전 결제가 성공이면 성공 응답 반환 (재결제 X) |
| 3   | **TossPayments 실제 중복** | Sandbox에서 동일 키를 두 번 전송                                   | TossPayments 자체 멱등성 응답 확인             |

### 모니터링으로 확인하는 것

- `payment.duplicate.blocked` 카운터 메트릭 → 차단된 중복 건수 수집
- k6 리포트: 100건 요청 중 성공 1건, 나머지 거절 응답 분포

### 이 Phase에서 얻는 인사이트

- 멱등성 키 저장 위치 (DB vs Redis)에 따른 트레이드오프
- TossPayments의 실제 Idempotency-Key 스펙과 우리의 서버 측 멱등성의 관계

### 측정 지표 (회고용)

- 중복 차단률 (100건 중 실제 PG 호출 건수: 1건)
- 멱등성 검증 추가 지연 시간 (ms)
- Sandbox 연동 전후 동작 차이

### ❓ 남은 문제 → Phase 4로

> "결제 하나만 처리할 때는 괜찮다. 결제 + 쿠폰 차감 + 포인트 사용을 함께 처리하다가 중간에 실패하면?"

---

## Phase 4. 보상 트랜잭션 (Compensating Transaction)

> "결제는 성공했는데 쿠폰 차감 중 에러가 발생했다. 어떻게 환불할 것인가?"

### 이전 Phase와의 차이 — 복잡도 증가

- 외부 시스템 추가: 쿠폰 서비스, 포인트 서비스 (WireMock 또는 별도 모듈)
- 다단계 파이프라인: 쿠폰 예약 → PG 승인 → 쿠폰 확정 → 포인트 차감

### 테스트로 유발할 문제

| #   | 시나리오           | 방법                                       | 관찰할 현상                           |
| --- | ------------------ | ------------------------------------------ | ------------------------------------- |
| 1   | **중간 단계 실패** | PG 승인 성공 후 쿠폰 확정 시점에 예외 발생 | 돈은 빠졌는데 쿠폰은 안 쓰인 상태     |
| 2   | **동기 보상 실패** | 환불 API 호출이 타임아웃                   | 돈도 안 돌아오고 쿠폰도 어중간한 상태 |

### 해결 — 동기적 보상 트랜잭션

- 다단계 실패 시 역순으로 취소 API 호출 (PG 환불 → 쿠폰 복구 → 포인트 복구)
- TossPayments Sandbox의 실제 환불 API 사용

### 모니터링으로 확인하는 것

- `payment.compensation.success` vs `payment.compensation.failed` 커스텀 카운터
- 다단계 파이프라인 전체 응답 시간 (서비스 증가에 따른 지연 누적)

### 이 Phase에서 얻는 인사이트

- 분산 환경에서 "롤백"이라는 개념이 존재하지 않는 이유
- 보상 트랜잭션의 한계: **보상 자체가 실패하면 어떻게 하는가?**
- 동기 호출 체인: 하나의 서비스 장애 → 전체 결제 실패 (강결합의 위험)

### 측정 지표 (회고용)

- 다단계 성공률 vs 부분 실패율
- 보상 트랜잭션 성공률
- 전체 파이프라인 평균 응답 시간 (Phase 1 대비)

### ❓ 남은 문제 → Phase 5로

> "보상이 실패하면 데이터가 영원히 불일치다. 동기 체인은 하나만 느려져도 전체가 느려진다."

---

## Phase 5. Outbox 패턴 + Kafka 비동기 이벤트 전환

> "DB 커밋과 이벤트 발행을 원자적으로 보장하면서 서비스 간 결합을 끊는다."

### 이전 Phase의 문제를 어떻게 해결하는가

- **Dual Write 문제**: DB 저장 + Kafka 발행을 따로 하면 둘 중 하나만 실패할 수 있다
- **Outbox 패턴**: 이벤트를 DB 트랜잭션과 함께 `outbox` 테이블에 저장 → 릴레이어가 Kafka에 발행
- 쿠폰/포인트/알림은 Kafka Consumer로 비동기 처리 → 강결합 해소

```
기존 (Phase 4):  결제 → 동기 호출 → 쿠폰 → 동기 호출 → 포인트 (강결합)
변경 (Phase 5):  결제 → DB + Outbox 저장 → [비동기] → 쿠폰 Consumer, 포인트 Consumer
```

### 테스트로 유발할 문제

| #   | 시나리오       | 방법                                | 관찰할 현상                                |
| --- | -------------- | ----------------------------------- | ------------------------------------------ |
| 1   | **폴링 지연**  | 릴레이어 폴링 주기를 30초로 설정    | 결제는 성공인데 쿠폰 차감이 30초 후 처리됨 |
| 2   | **중복 발행**  | 릴레이어가 같은 이벤트를 두 번 읽음 | 컨슈머 멱등성 없으면 쿠폰 두 번 차감       |
| 3   | **Kafka 장애** | Kafka 컨테이너 강제 종료            | Outbox에 미발행 이벤트 적체                |

### 모니터링으로 확인하는 것

- `outbox.pending.count` 커스텀 게이지 → 미발행 이벤트 적체 추이
- Kafka `consumer_lag` → 처리 지연 현황
- Grafana: Outbox 저장 → Kafka 발행까지의 지연 분포

### 이 Phase에서 얻는 인사이트

- Outbox 패턴이 **"DB 트랜잭션 + 이벤트 발행"의 원자성**을 어떻게 보장하는가
- Eventually Consistent의 의미 — "지금은 다르지만 결국엔 같아진다"
- 컨슈머 측 멱등성이 필수인 이유

### 측정 지표 (회고용)

- Outbox → Kafka 발행 평균 지연 시간 (폴링 주기별)
- 중복 발행 빈도
- Kafka 장애 시 미발행 이벤트 최대 적체량

### ❓ 남은 문제 → Phase 6로

> "폴링은 비효율적이다. DB에 부하를 주고 실시간성이 떨어진다. DB 변경을 실시간으로 감지할 방법이 없을까?"

---

## Phase 6. CDC — Kafka Connect + Debezium

> "Outbox 테이블을 폴링하지 말고, DB의 WAL(트랜잭션 로그)을 실시간으로 읽자."

### 이전 Phase의 문제를 어떻게 해결하는가

- **Debezium**(Kafka Connect Source Connector)이 PostgreSQL의 WAL을 읽어 Outbox 변경을 실시간으로 Kafka에 발행
- 폴링 프로세스 제거 → DB 부하 감소 + 실시간성 확보

```
Phase 5:  App → Outbox 저장 → [폴링 릴레이어] → Kafka        (수 초~수십 초 지연)
Phase 6:  App → Outbox 저장 → [Debezium CDC] → Kafka          (밀리초 단위 지연)
```

### 테스트로 유발할 문제

| #   | 시나리오                  | 방법                                                | 관찰할 현상                                          |
| --- | ------------------------- | --------------------------------------------------- | ---------------------------------------------------- |
| 1   | **Connector 장애/재시작** | Kafka Connect 컨테이너 강제 재시작                  | offset부터 재전송 → 중복 이벤트 발생                 |
| 2   | **컨슈머 처리 실패**      | 쿠폰 서비스 컨슈머가 예외 발생                      | 재처리 or 유실 — offset 관리 전략에 따라 결과 다름   |
| 3   | **이벤트 순서 역전**      | 같은 주문의 "결제 성공" → "환불" 이벤트가 순서 바뀜 | 환불이 먼저 처리된 뒤 결제 성공이 덮어쓰는 역전 현상 |

### 모니터링으로 확인하는 것

- Grafana: 폴링 대비 CDC의 발행 지연 비교 (초 → 밀리초)
- `kafka_consumer_lag` → 컨슈머 처리 지연
- Connector 상태 모니터링 (REST API `/connectors/{name}/status`)

### 이 Phase에서 얻는 인사이트

- CDC의 동작 원리 (WAL 기반 vs 폴링 기반)
- 이벤트 순서 보장을 위한 Kafka 파티션 키 설계
- Consumer 멱등성 + offset 관리의 중요성

### 측정 지표 (회고용)

- Outbox 저장 → Kafka 도착 지연 (폴링 vs CDC 비교)
- Connector 재시작 시 중복 이벤트 수
- 이벤트 역전 발생 빈도

### ❓ 남은 문제 → Phase 7로

> "컨슈머가 계속 실패하는 이벤트는 어떻게 처리하는가? 시스템 간 데이터가 미세하게 어긋나면 어떻게 감지하는가?"

---

## Phase 7. 최후의 안전망 — DLQ + 대사(Reconciliation) + 운영 모니터링

> "어디서 장애가 나도, 데이터는 **결국** 일치해야 한다."

### 7-1. Dead Letter Queue (DLQ)

- 컨슈머가 N회 재시도 후에도 실패한 이벤트 → DLQ 토픽으로 격리
- DLQ 이벤트는 별도 처리기가 분석 후 재처리 or 운영자 알림

### 7-2. 대사(Reconciliation) 스케줄러

- `@Scheduled` 또는 Spring Batch로 주기적으로:
  - 결제 DB `SUCCESS` 건수 vs 쿠폰 서비스 차감 건수 비교
  - TossPayments 정산 데이터 vs 내부 결제 데이터 비교
  - 불일치 발견 시 `MISMATCH` 상태 마킹 + 슬랙 알림

### 7-3. 운영 Grafana 대시보드 완성

- DLQ 적체량 알림
- `PENDING` 장기 체류 알림 (Phase 2의 Unknown State 최종 방어)
- Reconciliation 불일치 건수 추이
- 전체 파이프라인 이상 감지 알림 연동

### 테스트

| #   | 시나리오                                         | 검증                                   |
| --- | ------------------------------------------------ | -------------------------------------- |
| 1   | 쿠폰 컨슈머 3회 실패 → DLQ 이동                  | DLQ에 해당 이벤트 도착 확인            |
| 2   | 의도적 불일치 데이터 생성 → Reconciliation 실행  | 불일치 감지 + 알림 발송 확인           |
| 3   | E2E: 결제 → 쿠폰 실패 → DLQ → 재처리 → 최종 일치 | 전체 파이프라인이 "결국 일치"함을 증명 |

### 이 Phase에서 얻는 인사이트

- **"결국 일치한다(Eventually Consistent)"** 를 운영 레벨에서 보장하는 방법
- 대사(Reconciliation)가 **실무에서 왜 필수인지** 직접 경험
- 모니터링 없는 비동기 시스템은 **블랙홀**이라는 교훈

---

## 전체 기술 도입 흐름 요약

| Phase | 핵심 주제             | 새로 도입하는 기술/패턴                                | 해결하는 문제                    | 발견하는 새 문제           |
| ----- | --------------------- | ------------------------------------------------------ | -------------------------------- | -------------------------- |
| 1     | 나이브한 결제         | WireMock, k6, Prometheus+Grafana                       | -                                | 커넥션 고갈, 데이터 불일치 |
| 2     | TX 분리 + 상태 머신   | 상태 머신 (PENDING/SUCCESS/FAILED)                     | 커넥션 고갈                      | Unknown State, 타임아웃    |
| 3     | 멱등성 + TossPayments | Idempotency-Key, Redis/DB Unique, TossPayments Sandbox | 중복 결제                        | 다단계 처리 실패           |
| 4     | 보상 트랜잭션         | 쿠폰/포인트 서비스 추가, 보상 패턴                     | 다단계 실패 복구                 | 보상 자체 실패, 강결합     |
| 5     | Outbox + Kafka        | Kafka, Outbox 테이블, 폴링 릴레이어, 알림 서비스 추가  | 원자적 이벤트 발행, 느슨한 결합  | 폴링 비효율, 중복 발행     |
| 6     | CDC                   | Kafka Connect, Debezium                                | 실시간 이벤트 발행, DB 부하 감소 | 컨슈머 장애, 순서 역전     |
| 7     | 최후의 안전망         | DLQ, Spring Batch Reconciliation, Slack 알림           | 최종 일관성 보장, 운영 가시성    | - (운영 안정 상태)         |

---

## 학습 완료 시 답할 수 있는 질문들

> "외부 API를 `@Transactional`에 넣으면 안 되는 이유?"
> → Phase 1에서 커넥션 풀이 **N초 만에 고갈**되고, 불일치가 **N건** 발생했습니다.

> "PG사 응답이 타임아웃이면 결제된 건가 안 된 건가?"
> → 알 수 없습니다(Unknown State). Phase 2에서 PENDING 상태 + 조회 API polling으로 복구했습니다.

> "결제 버튼 따닥 누르면?"
> → Phase 3에서 Idempotency-Key로 방어해서 100건 동시 요청 중 **1건만** PG로 전달됩니다.

> "TossPayments의 Idempotency-Key와 우리 서버의 멱등성은 다른가?"
> → 다릅니다. TossPayments 키는 PG 측 중복 방지, 서버 측 키는 우리 시스템 진입 중복 방지입니다.

> "Kafka 없이 직접 API 호출하면 안 돼?"
> → Phase 4에서 동기 호출 시 하나의 서비스 장애가 전체 결제를 **N% 실패**시키는 것을 측정했습니다.

> "Outbox 패턴이 뭔데 왜 필요해?"
> → Phase 5에서 DB 커밋 + 이벤트 발행이 원자적이지 않으면 **N건의 유실**이 발생하는 것을 확인했습니다.

> "CDC가 폴링보다 나은 이유?"
> → Phase 6에서 발행 지연이 **N초 → N밀리초**로 개선되고, DB 부하가 **N% 감소**했습니다.

> "결국 데이터 일치는 어떻게 보장해?"
> → Phase 7의 Reconciliation 스케줄러가 **N분 주기**로 불일치를 감지하고, DLQ를 통해 실패 이벤트를 재처리합니다.
