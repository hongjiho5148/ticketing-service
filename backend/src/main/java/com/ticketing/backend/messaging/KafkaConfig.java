package com.ticketing.backend.messaging;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 소비자 전용이다(backend는 더는 reservation.created를 발행하지 않는다. 이제 도메인을 소유한 reservation-service가 한다).
 * 일반 String 페이로드용으로 구성했다. ReservationEventListener가 spring-kafka의 JsonDeserializer가 아니라
 * 앱 자체의 Jackson 3 ObjectMapper로 변환한다. JsonDeserializer는 기존 Jackson 2 위에 만들어져서 이 앱의
 * Jackson 3 클래스패스에서는 해석되지 않는다. 또한 Spring Boot가 자동 구성하는 리스너 컨테이너 팩토리가
 * 일반적으로 <Object, Object>라서 타입이 있는 주입 지점을 어느 쪽으로도 만족시키지 못한다는 점도 피한다.
 *
 * <p>리스너가 처리하지 못하는 메시지(읽을 수 없는 페이로드, 버그)는 몇 번 재시도한 뒤 "<topic>.DLT"로 옮겨서
 * 파티션이 계속 움직이게 하고 레코드는 보존한다. 이것이 없으면 프레임워크의 기본 동작은 로그만 남기고 건너뛰는
 * 것, 즉 잃어버리는 것이다. 데드 레터는 원래 페이로드를 그대로 가지므로 원인을 고친 뒤 살펴보고 다시 재생할 수 있다.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, String> stringConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId) {
        Map<String, Object> configs = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    /** 데드 레터를 발행하는 데만 쓴다. 여기 있는 다른 것들처럼 일반 String이다. */
    @Bean
    public KafkaTemplate<String, String> deadLetterKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configs = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(configs);
        return new KafkaTemplate<>(producerFactory);
    }

    /** 1초 간격으로 세 번 더 시도한 뒤 데드 레터 토픽으로 보낸다(파티션은 프로듀서에 맡긴다). */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate, (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> stringConsumerFactory, DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stringConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
