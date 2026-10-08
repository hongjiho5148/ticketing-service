package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.dto.CheckoutResponse;
import com.ticketing.orderservice.order.dto.OrderHistoryResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picking a pending payment back up: allowed only while the reservation still holds the seat. The other
 * services are mocked; the order rows are real (compose MySQL, rolled back). Run inside the compose
 * network with DB_HOST=mysql, like the other order-service tests.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class ResumeCheckoutTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private ReservationServiceClient reservationServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        when(eventServiceClient.getSeats(anyList())).thenAnswer(inv ->
                ((List<Long>) inv.getArgument(0)).stream()
                        .map(id -> new SeatDetailResponse(id, 777L, "이어서 결제 테스트", "홀",
                                LocalDateTime.now().plusDays(20), "R", "A구역", 1, 1, "1열 1번", 10000, "HOLD"))
                        .toList());
    }

    private Orders pendingOrder() {
        long id = SEQ.incrementAndGet();
        return orderRepository.save(new Orders(userId, id, id + 1_000, LocalDateTime.now().plusDays(20), 777L, 10000));
    }

    private void reservationIs(Orders order, String status, LocalDateTime holdExpireAt) {
        when(reservationServiceClient.getReservation(order.getReservationId()))
                .thenReturn(new ReservationDetailResponse(order.getReservationId(), userId, order.getSeatId(), status, holdExpireAt));
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private void makeOlder(Orders order, int minutes) {
        em.flush();
        em.createNativeQuery("update orders set created_at = ? where id = ?")
                .setParameter(1, LocalDateTime.now().minusMinutes(minutes))
                .setParameter(2, order.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void aPendingOrderWithALiveHoldCanBeResumed() {
        Orders order = pendingOrder();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(3);
        reservationIs(order, "HOLDING", expires);

        CheckoutResponse checkout = orderService.resumeCheckout(userId, order.getId());

        assertThat(checkout.order().orderId()).isEqualTo(order.getId());
        assertThat(checkout.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(checkout.seatId()).isEqualTo(order.getSeatId());
        assertThat(checkout.eventId()).isEqualTo(777L);
        assertThat(checkout.holdExpireAt()).isEqualTo(expires);
    }

    @Test
    void aLapsedOrReleasedHoldCannotBeResumed() {
        Orders lapsed = pendingOrder();
        reservationIs(lapsed, "HOLDING", LocalDateTime.now().minusSeconds(5));
        expectError(() -> orderService.resumeCheckout(userId, lapsed.getId()), ErrorCode.ORDER_NOT_RESUMABLE);

        Orders expired = pendingOrder();
        reservationIs(expired, "EXPIRED", LocalDateTime.now().plusMinutes(3));
        expectError(() -> orderService.resumeCheckout(userId, expired.getId()), ErrorCode.ORDER_NOT_RESUMABLE);

        Orders cancelled = pendingOrder();
        reservationIs(cancelled, "CANCELLED", LocalDateTime.now().plusMinutes(3));
        expectError(() -> orderService.resumeCheckout(userId, cancelled.getId()), ErrorCode.ORDER_NOT_RESUMABLE);
    }

    @Test
    void anUnreachableReservationServiceMeansNotResumable() {
        Orders order = pendingOrder();
        when(reservationServiceClient.getReservation(order.getReservationId())).thenThrow(new IllegalStateException("down"));

        expectError(() -> orderService.resumeCheckout(userId, order.getId()), ErrorCode.ORDER_NOT_RESUMABLE);
    }

    @Test
    void anOrderOlderThanAnyHoldIsRefusedWithoutAskingReservationService() {
        Orders order = pendingOrder();
        reservationIs(order, "HOLDING", LocalDateTime.now().plusMinutes(3)); // even if it (impossibly) looked alive
        makeOlder(order, 30);

        expectError(() -> orderService.resumeCheckout(userId, order.getId()), ErrorCode.ORDER_NOT_RESUMABLE);

        verify(reservationServiceClient, never()).getReservation(anyLong());
    }

    @Test
    void onlyTheBuyerCanResumeAndOnlyWhilePending() {
        Orders order = pendingOrder();
        reservationIs(order, "HOLDING", LocalDateTime.now().plusMinutes(3));

        expectError(() -> orderService.resumeCheckout(userId + 1, order.getId()), ErrorCode.FORBIDDEN);
        expectError(() -> orderService.resumeCheckout(userId, 0L), ErrorCode.ORDER_NOT_FOUND);

        order.markPaid();
        expectError(() -> orderService.resumeCheckout(userId, order.getId()), ErrorCode.ORDER_NOT_PENDING);
    }

    @Test
    void theOrderListShowsTheRemainingHoldOnlyWhereThereIsOne() {
        Orders live = pendingOrder();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(2);
        reservationIs(live, "HOLDING", expires);

        Orders lapsed = pendingOrder();
        reservationIs(lapsed, "EXPIRED", LocalDateTime.now().minusMinutes(1));

        Orders old = pendingOrder();
        makeOlder(old, 60);

        Orders paid = pendingOrder();
        paid.markPaid();
        em.flush();

        List<OrderHistoryResponse> rows = orderService.getOrders(userId, PageRequest.of(0, 20)).content();

        assertThat(rows).hasSize(4);
        assertThat(holdOf(rows, live)).isEqualTo(expires);
        assertThat(holdOf(rows, lapsed)).isNull();
        assertThat(holdOf(rows, old)).isNull();
        assertThat(holdOf(rows, paid)).isNull();
        verify(reservationServiceClient, never()).getReservation(old.getReservationId());
        verify(reservationServiceClient, never()).getReservation(paid.getReservationId());
    }

    private static LocalDateTime holdOf(List<OrderHistoryResponse> rows, Orders order) {
        return rows.stream().filter(r -> r.orderId().equals(order.getId())).findFirst().orElseThrow().holdExpireAt();
    }
}
