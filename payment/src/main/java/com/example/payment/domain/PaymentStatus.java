package com.example.payment.domain;

public enum PaymentStatus {
    READY,
    // TX1 사전 커밋 시 결제 레코드 초기 상태.
    PENDING,
    SUCCESS,
    FAILED,
    // PG 호출 타임아웃 시 승인 여부 불명 상태.
    // 복구 스케줄러(UnknownPaymentRecoveryScheduler)가 1분마다 PG 조회 API로 최종 상태를 확정한다.
    UNKNOWN
}
