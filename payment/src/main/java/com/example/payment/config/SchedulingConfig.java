package com.example.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화 설정.
 *
 * PaymentApplication에 직접 @EnableScheduling을 붙이지 않고 별도 설정 클래스로 분리한 이유:
 * @SpringBootTest로 전체 컨텍스트를 로드하는 테스트에서 스케줄러가 자동 실행되면
 * UnknownPaymentRecoveryScheduler가 테스트 DB 데이터를 오염시킬 수 있다.
 * 테스트에서는 이 설정 클래스를 제외하거나 scheduler.recover()를 직접 호출하여 검증한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
