package com.ticketing.reservationservice.messaging;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 프로듀서 전용이다(reservation-service는 어떤 토픽도 소비하지 않는다). 일반 String 페이로드용으로 구성했다.
 * 이벤트 레코드는 spring-kafka의 JsonSerializer가 아니라 OutboxRecorder에서 앱 자체의 Jackson 3
 * ObjectMapper로 JSON으로 만든다. JsonSerializer는 기존 Jackson 2 위에 만들어져서 이 앱의
 * Jackson 3 클래스패스에서는 해석되지 않는다.
 *
 * <p>OutboxRelay는 전송마다 완료를 기다리므로 타임아웃이 짧다(브로커가 죽었으면 기본 1분이 아니라 몇 초 안에
 * 전송이 실패해야 한다). acks=all은 "확인됨"이 브로커가 실제로 받았다는 뜻이 되게 하고, 릴레이는 그 뒤에야
 * 이벤트를 전송 완료로 표시한다.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configs = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        return new DefaultKafkaProducerFactory<>(configs);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> stringProducerFactory) {
        return new KafkaTemplate<>(stringProducerFactory);
    }
}
