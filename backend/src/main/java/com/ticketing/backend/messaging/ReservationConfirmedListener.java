package com.ticketing.backend.messaging;

import com.ticketing.backend.queue.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 결제를 마친 구매자는 대기열에서 할 일이 끝났으므로, 그 사람의 활성 슬롯을 돌려준다. 그러면 입장권 토큰이
 * 만료되기를 기다리지 않고 입장 스위퍼의 다음 주기에 다음 대기자를 들여보낼 수 있다.
 *
 * <p>전달은 최소 한 번(at-least-once)이라 두 번 받아도 안전해야 하고, 실제로 그렇다. 이미 돌려준 슬롯을 다시
 * 돌려줘도 아무것도 바뀌지 않는다. 읽을 수 없는 메시지는 컨테이너의 오류 처리기가 재시도한 뒤 데드 레터로
 * 보낸다(KafkaConfig 참고).
 */
@Component
public class ReservationConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmedListener.class);

    private final QueueService queueService;
    private final ObjectMapper objectMapper;

    public ReservationConfirmedListener(QueueService queueService, ObjectMapper objectMapper) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.RESERVATION_CONFIRMED, groupId = "backend")
    public void onReservationConfirmed(String payload) {
        ReservationConfirmedEvent event = objectMapper.readValue(payload, ReservationConfirmedEvent.class);
        int released = queueService.releaseSlotsOfBuyer(event.userId(), event.eventId());
        log.info("Reservation {} confirmed: handed back {} queue slot(s) of user {} for event {}",
                event.reservationId(), released, event.userId(), event.eventId());
    }
}
