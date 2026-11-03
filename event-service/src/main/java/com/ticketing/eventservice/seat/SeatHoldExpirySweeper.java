package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.alert.SeatsReleasedEvent;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스스로 치유하는 안전망이다. 호출한 쪽(backend의 Reservation)이 해제를 끝내 요청하는지와 상관없이 holdExpireAt을
 * 넘겨서 HOLD에 멈춰 있는 좌석을 푼다. 홀드와 Reservation 행이 이제 서로 다른 서비스/DB에 있으므로, 이것이
 * 고아가 된 홀드(예: backend가 홀드 호출에 성공한 직후 죽은 경우)를 영원이 아니라 몇 분으로 한정한다.
 * backend의 ReservationExpirySweeper도 자기 쪽에서 같은 해제 호출을 한다. 둘이 이미 AVAILABLE인 좌석에 닿아도
 * release()가 그 상태를 조건 없이 설정할 뿐이라 해가 없다. 이 스위퍼가 푼 좌석은 공연별로 알려서 취소표 대기자가
 * 소식을 듣게 한다.
 */
@Component
public class SeatHoldExpirySweeper {

    private final SeatRepository seatRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SeatHoldExpirySweeper(SeatRepository seatRepository, ApplicationEventPublisher eventPublisher) {
        this.seatRepository = seatRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "${seat.hold-expiry-sweep-cron}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Seat> expired = seatRepository.findByStatusAndHoldExpireAtBefore(SeatStatus.HOLD, LocalDateTime.now());
        Map<Long, Integer> releasedPerEvent = new HashMap<>();
        for (Seat seat : expired) {
            seat.release();
            releasedPerEvent.merge(seat.getEvent().getId(), 1, Integer::sum);
        }
        releasedPerEvent.forEach((eventId, count) -> eventPublisher.publishEvent(new SeatsReleasedEvent(eventId, count)));
    }
}
