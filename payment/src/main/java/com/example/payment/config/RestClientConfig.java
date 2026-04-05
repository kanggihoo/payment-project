package com.example.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient pgRestClient() {
        // HTTP 요청 설정을 위한 팩토리 객체 생성
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        
        // [1] 연결 타임아웃: 외부 PG 서버와 네트워크 연결을 맺는 시도 시간 (최대 5초)
        factory.setConnectTimeout(5_000); 
        
        // [2] 읽기 타임아웃: 연결 후 응답 데이터를 받기 위해 기다리는 시간 (최대 30초)
        // PG사의 결제 처리가 지연될 수도 있으므로 넉넉하게 30초로 설정함
        factory.setReadTimeout(30_000); 

        // 위 설정을 적용하여 RestClient 인스턴스를 생성하고 스프링 빈(Bean)으로 등록
        // 이제 시스템 어디서든 이 설정을 가진 RestClient를 주입받아 사용할 수 있습니다.
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
