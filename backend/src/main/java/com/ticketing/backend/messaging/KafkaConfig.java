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
 * Consumer-only (backend no longer publishes reservation.created - reservation-service does, since
 * it owns the domain now). Wired for a plain String payload - ReservationEventListener marshals it
 * with the app's own Jackson 3 ObjectMapper instead of spring-kafka's JsonDeserializer, which is
 * built on classic Jackson 2 and doesn't resolve on this app's Jackson 3 classpath. Also sidesteps
 * the fact that Spring Boot's auto-configured listener-container-factory is generically
 * <Object, Object>, which doesn't satisfy a typed injection point either way.
 *
 * <p>A message the listener can't process (unreadable payload, a bug) is retried a few times and then moved to
 * "<topic>.DLT" so the partition keeps moving and the record is kept - without this the framework's default is to
 * log it and skip it, i.e. lose it. The dead letters keep their original payload, so they can be inspected and
 * replayed once the cause is fixed.
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

    /** Only used to publish dead letters; plain Strings like everything else here. */
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

    /** Three more attempts one second apart, then the dead-letter topic (partition left to the producer). */
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
