package com.ticketing.eventservice.event;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 구간이 끝난 공연(시작 Event.SALES_CLOSE_BEFORE_START 전)을 닫힘으로 표시해서 목록에는 "예매종료"가 나오고
 * 상세 화면은 좌석을 더는 보여 주지 않는다. 상태는 눈에 보이는 쪽일 뿐이다. 좌석을 잡을 수 있는지는 시계로
 * 판정하므로(Event.isSalesClosed) 이 작업이 1분 늦는다고 해서 예매할 수 있는 것이 달라지지는 않는다.
 * OpenAlertSweeper와 같은 크론 스윕 구조다.
 */
@Component
public class SalesCloseSweeper {

    private static final Logger log = LoggerFactory.getLogger(SalesCloseSweeper.class);

    private final EventRepository eventRepository;

    public SalesCloseSweeper(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Scheduled(cron = "${event.sales-close-sweep-cron}")
    @Transactional
    public void closeFinishedSales() {
        LocalDateTime cutoff = LocalDateTime.now().plus(Event.SALES_CLOSE_BEFORE_START);
        int closed = 0;
        for (Event event : eventRepository.findByStatusNotAndStartAtLessThanEqual(EventStatus.CLOSED, cutoff)) {
            event.close();
            closed++;
        }
        if (closed > 0) {
            log.info("closed sales of {} shows that start within {}", closed, Event.SALES_CLOSE_BEFORE_START);
        }
    }
}
