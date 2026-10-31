package com.ticketing.eventservice.event;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a show closed once its sales window is over (Event.SALES_CLOSE_BEFORE_START before it starts), so the list says
 * "예매종료" and the detail page stops offering seats. The status is only the visible side of it: whether a seat can be
 * taken is decided from the clock (Event.isSalesClosed), so the minute this runs behind makes no difference to what can be
 * booked. Same cron-sweep shape as OpenAlertSweeper.
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
