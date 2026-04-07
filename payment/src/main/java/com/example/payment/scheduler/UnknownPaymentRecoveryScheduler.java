package com.example.payment.scheduler;

import com.example.payment.client.PgClient;
import com.example.payment.client.PgPaymentException;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.PaymentInternalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UNKNOWN 상태 결제 복구 스케줄러.
 *
 * Phase 2에서 해결하는 문제:
 * PG 타임아웃 시 결제 승인 여부를 알 수 없어 Payment=UNKNOWN으로 기록한다.
 * 이 스케줄러가 1분 주기로 PG 조회 API를 호출해 최종 상태를 확정함으로써
 * "돈은 빠졌는데 기록 없음" 상태가 영구적으로 지속되는 것을 방지한다.
 *
 * @Scheduled는 SchedulingConfig의 @EnableScheduling이 있어야 동작한다.
 * SchedulingConfig를 별도 분리한 이유:
 * PaymentApplication에 직접 붙이면 테스트 컨텍스트 로딩 시 스케줄러가 자동 실행되어
 * 다른 테스트 데이터를 오염시킬 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnknownPaymentRecoveryScheduler {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentInternalService paymentInternalService;

    /**
     * UNKNOWN 결제를 조회하여 PG 최종 상태로 확정한다.
     *
     * - PG DONE  → recordSuccess (Payment=SUCCESS, Order=SUCCESS)
     * - PG 404   → recordFailure (Payment=FAILED, Order=FAILED)
     * - 네트워크 에러 → UNKNOWN 유지, 경고 로그 후 다음 주기에 재시도
     */
    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        var unknownPayments = paymentRepository.findByStatus(PaymentStatus.UNKNOWN);

        for (var payment : unknownPayments) {
            try {
                var pgResponse = pgClient.queryPayment(payment.getPgPaymentKey());

                if ("DONE".equals(pgResponse.status())) {
                    // PG에서 승인 완료 확인 → SUCCESS로 확정
                    paymentInternalService.recordSuccess(payment.getId(), payment.getPgPaymentKey());
                    log.info("UNKNOWN 복구 완료 - paymentId={}, status=SUCCESS", payment.getId());
                } else {
                    // DONE 외 상태(예: CANCELED) → FAILED로 처리
                    paymentInternalService.recordFailure(payment.getId());
                    log.info("UNKNOWN 복구 완료 - paymentId={}, status=FAILED (pgStatus={})",
                            payment.getId(), pgResponse.status());
                }

            } catch (PgPaymentException e) {
                if (isNetworkError(e)) {
                    // 네트워크 에러 — UNKNOWN 유지, 다음 주기에 재시도
                    log.warn("UNKNOWN 복구 실패 (네트워크 에러), 다음 주기에 재시도 - paymentId={}, error={}",
                            payment.getId(), e.getMessage());
                } else {
                    // 4xx (결제 키 없음 등) → FAILED로 확정
                    paymentInternalService.recordFailure(payment.getId());
                    log.info("UNKNOWN 복구 완료 - paymentId={}, status=FAILED (pgError={})",
                            payment.getId(), e.getMessage());
                }
            }
        }
    }

    // PgClient에서 네트워크 오류는 "PG 조회 요청 실패:"로 시작한다
    private boolean isNetworkError(PgPaymentException e) {
        return e.getMessage() != null && e.getMessage().startsWith("PG 조회 요청 실패");
    }
}
