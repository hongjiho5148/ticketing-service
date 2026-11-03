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
 * @Transactional 테스트로는 보이지 않던 버그의 회귀 테스트다. 환불 미리보기는 READ ONLY 트랜잭션에서 실행되는데,
 * MySQL은 거기서 SELECT ... FOR UPDATE를 거절한다("Cannot execute statement in a READ ONLY transaction").
 * 그래서 취소 행 잠금을 잡던 미리보기가 운영에서 500으로 실패했다. 롤백 테스트에서는 바깥의 읽기-쓰기 트랜잭션이
 * readOnly 플래그를 삼켜 버리므로, 이 클래스는 일부러 @Transactional로 하지 않았다. 실제 행을 커밋하고 다시 지운다.
 * 다른 order-service 테스트처럼 compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
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
