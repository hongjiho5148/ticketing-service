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
 * Producer-only (reservation-service doesn't consume any topic). Wired for plain String payloads -
 * the event record is marshalled to JSON with the app's own Jackson 3 ObjectMapper in
 * OutboxRecorder, instead of spring-kafka's JsonSerializer, which is built on classic
 * Jackson 2 and doesn't resolve on this app's Jackson 3 classpath.
 *
 * <p>OutboxRelay waits for each send, so the timeouts are short (a down broker should fail a send in seconds, not the
 * default minute) and acks=all makes "acknowledged" mean the broker really has it - the relay marks an event sent only
 * after that.
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
