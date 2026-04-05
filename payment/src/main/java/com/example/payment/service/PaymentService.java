package com.example.payment.service;

import com.example.payment.client.PgApproveResponse;
import com.example.payment.client.PgClient;
import com.example.payment.domain.Order;
import com.example.payment.domain.OrderStatus;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.repository.OrderRepository;
import com.example.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    /**
     * [참사 1단계: 나이브한 결제 처리]
     * 이 메서드는 외부 API 호출을 @Transactional 애플리케이션 서비스 안에 넣었을 때 
     * 발생할 수 있는 치명적인 문제들을 시뮬레이션하기 위해 작성되었습니다.
     * 
     
     */
    @Transactional
    public PaymentResult processPayment(String orderNumber) {
        // 1. 주문 데이터 조회
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderNumber));

        /**
         * ⚠️ 치명적 문제 1: DB 트랜잭션 안에서 외부 HTTP(PG사) 호출
         * 
         * [참사 시나리오: 커넥션 풀 고갈]
         * 1. @Transactional이 시작되면서 DB 커넥션 풀(HikariCP)에서 커넥션을 하나 점유합니다.
         * 2. pgClient.approve()가 호출되어 외부 서버의 응답을 기다립니다.
         * 3. 만약 PG사가 응답에 10초가 걸린다면, 이 커넥션은 10초 동안 아무것도 안 하고 묶여있게 됩니다.
         * 4. 동시 요청이 많아지면 모든 커넥션이 외부 응답을 기다리느라 고갈되어, 
         *    결국 시스템 전체(로그인, 단순 조회 등)가 마비되는 현상이 발생
         */
        PgApproveResponse pgResponse = pgClient.approve(order.getOrderNumber(), order.getAmount());

        /**
         * ⚠️ 치명적 문제 2: 결제 승인 완료 후 DB 저장 시점의 롤백 위험
         * 
         * [참사 시나리오: 데이터 불일치/돈만 빠져나감]
         * 1. 위에서 pgClient.approve()는 이미 성공하여 고객의 돈은 빠져나간 상태입니다.
         * 2. 아래의 paymentRepository.save()나 order.setStatus() 과정에서 에러가 발생하면?
         * 3. @Transactional에 의해 DB는 롤백되지만, 이미 성공한 외부 결제(PG)는 취소되지 않습니다.
         * 4. 결과: 고객은 돈을 지불했지만, 우리 서버에는 결제 기록이 없는 '데이터 부정합' 상태가 됩니다.
         */
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setAmount(order.getAmount());
        payment.setPgPaymentKey(pgResponse.paymentKey());
        payment.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(payment);

        order.setStatus(OrderStatus.SUCCESS);
        
        return new PaymentResult(order.getOrderNumber(), "SUCCESS", pgResponse.paymentKey());
    }
}
