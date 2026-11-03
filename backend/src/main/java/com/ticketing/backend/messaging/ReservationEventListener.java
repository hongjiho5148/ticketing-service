package com.ticketing.backend.messaging;

import com.ticketing.backend.queue.AdmissionUseTracker;
import com.ticketing.backend.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 예약 생성 이벤트가 대기열에 어떤 의미인지를 읽는다. 좌석을 잡은 사람은 구매 중이므로 입장권을 연장한다
 * (QueueService.extendPassesOfHolder). 이것은 반드시 동작해야 하므로 거기서 난 오류는 다른 것과 마찬가지로
 * 재시도한 뒤 데드 레터로 보낸다. 입장하고 얼마 만에 좌석을 잡았는지는 메트릭을 위해서만 기록하며
 * (AdmissionUseTracker), 그 부분의 실패는 로그만 남기고 재시도하지 않는다.
 */
@Component
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    private final ObjectMapper objectMapper;
    private final AdmissionUseTracker admissionUseTracker;
    private final QueueService queueService;

    public ReservationEventListener(ObjectMapper objectMapper, AdmissionUseTracker admissionUseTracker, QueueService queueService) {
        this.objectMapper = objectMapper;
        this.queueService = queueService;
        this.admissionUseTracker = admissionUseTracker;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CREATED, groupId = "backend")
    public void onReservationCreated(String payload) {
        ReservationCreatedEvent event = objectMapper.readValue(payload, ReservationCreatedEvent.class);
        log.info("Received {}: {}", KafkaTopics.RESERVATION_CREATED, event);
        queueService.extendPassesOfHolder(event.userId(), event.eventId());
        try {
            admissionUseTracker.onReservationCreated(event.userId(), event.eventId(), event.createdAt());
        } catch (RuntimeException e) {
            log.warn("could not record how soon user {} reserved after being let in", event.userId(), e);
        }
    }
}
