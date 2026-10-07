package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression test for a bug the @Transactional tests could not see: the refund preview runs in a
 * READ ONLY transaction, and MySQL rejects SELECT ... FOR UPDATE there ("Cannot execute statement in
 * a READ ONLY transaction") - so a preview that took the cancellation row lock failed with a 500 in
 * production. Inside a rollback test the outer read-write transaction swallows the readOnly flag, so this
 * class is deliberately NOT @Transactional: it commits real rows and deletes them again.
 * Run inside the compose network with DB_HOST=mysql, like the other order-service tests.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
class RefundPreviewTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;

    private final List<Orders> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll(created);
    }

    private Orders paidOrder(long userId, LocalDateTime eventStartAt) {
        long id = SEQ.incrementAndGet();
        Orders order = new Orders(userId, id, id, eventStartAt, 777L, 10000);
        order.markPaid();
        Orders saved = orderRepository.save(order);
        created.add(saved);
        return saved;
    }

    @Test
    void previewWorksOutsideATransaction() {
        long userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        Orders order = paidOrder(userId, LocalDateTime.now().plusDays(10));

        RefundQuote quote = orderService.refundPreview(userId, order.getId());

        assertThat(quote.isCancellable()).isTrue();
        assertThat(quote.refundPercent()).isEqualTo(100);
        assertThat(quote.refundAmount()).isEqualTo(10000);
    }

    @Test
    void previewReportsTheRightRefundTierAndRefusesTransferredTickets() {
        long userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        Orders soon = paidOrder(userId, LocalDateTime.now().plusDays(2));
        assertThat(orderService.refundPreview(userId, soon.getId()).refundPercent()).isEqualTo(30);

        Orders given = paidOrder(userId, LocalDateTime.now().plusDays(10));
        given.transferTo(userId + 1);
        orderRepository.save(given);
        assertThatThrownBy(() -> orderService.refundPreview(userId, given.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_TRANSFERRED));
    }
}
