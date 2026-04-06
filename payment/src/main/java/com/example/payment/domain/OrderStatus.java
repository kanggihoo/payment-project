package com.example.payment.domain;

public enum OrderStatus {
    READY,
    // TX1 사전 커밋 후 PG 호출 전까지의 중간 상태.
    // 이 상태에서 DB 커넥션이 반납되어 커넥션 풀 고갈 문제를 해결한다.
    PENDING,
    SUCCESS,
    FAILED
}
