-- Orders Table
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(255) UNIQUE NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Payment Table
CREATE TABLE payment (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    pg_payment_key VARCHAR(255),
    amount BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Seed Data
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-001', 1000, 'READY');
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-002', 5000, 'READY');
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-PENDING', 2000, 'PENDING');
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-SUCCESS', 3000, 'SUCCESS');
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-UNKNOWN', 4000, 'PENDING');

-- PaymentServiceOrchestrationTest - PG 타임아웃(UNKNOWN) 시나리오 전용 주문
INSERT INTO orders (order_number, amount, status) VALUES ('ORD-PENDING-2', 6000, 'READY');

-- UNKNOWN 상태 결제 레코드 (복구 스케줄러 테스트용)
INSERT INTO payment (order_id, pg_payment_key, amount, status)
  SELECT id, 'PG-KEY-UNKNOWN-001', 4000, 'UNKNOWN' FROM orders WHERE order_number = 'ORD-UNKNOWN';
