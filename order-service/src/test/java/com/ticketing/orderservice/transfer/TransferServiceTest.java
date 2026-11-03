package com.ticketing.orderservice.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.authclient.AuthServiceClient;
import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.internal.OrderInternalService;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderService;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.ticket.Ticket;
import com.ticketing.orderservice.ticket.TicketRepository;
import com.ticketing.orderservice.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * compose MySQL(롤백)에서 확인하는 티켓 양도 규칙이다. auth/event 조회와 알림은 mock이고, 주문 행 잠금,
 * 대기 중인 양도는 하나뿐이라는 제약, 원자적인 상태 전환이 실제로 실행해 보는 부분이다.
 * OrderBenefitServiceTest처럼 compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class TransferServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private TransferService transfers;
    @Autowired private OrderService orderService;
    @Autowired private OrderInternalService orderInternalService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketTransferRepository transferRepository;
    @PersistenceContext private EntityManager em;

    @MockitoBean private AuthServiceClient authServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private TransferNotifier notifier;

    private Long sellerId;
    private Long buyerId;

    @BeforeEach
    void setUp() {
        long n = SEQ.incrementAndGet();
        sellerId = 9_000_000_000L + n % 1_000_000;
        buyerId = sellerId + 1;
        when(authServiceClient.getUser(sellerId))
                .thenReturn(new UserInternalResponse(sellerId, "seller@example.com", "보내는사람", true));
        when(authServiceClient.lookupByEmail("buyer@example.com"))
                .thenReturn(new UserInternalResponse(buyerId, "buyer@example.com", "받는사람", true));
        when(authServiceClient.lookupByEmail("seller@example.com"))
                .thenReturn(new UserInternalResponse(sellerId, "seller@example.com", "보내는사람", true));
        when(eventServiceClient.getSeat(anyLong())).thenAnswer(inv -> seat(inv.getArgument(0)));
        when(eventServiceClient.getSeats(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv ->
                ((List<Long>) inv.getArgument(0)).stream().map(this::seat).toList());
    }

    private SeatDetailResponse seat(Long seatId) {
        return new SeatDetailResponse(seatId, 1L, "양도 테스트 공연", "홀", LocalDateTime.now().plusDays(3),
                "R", "A구역", 1, 1, "1열 1번", 10000, "SOLD");
    }

    private Orders paidOrder(LocalDateTime eventStartAt) {
        long id = SEQ.incrementAndGet();
        Orders order = orderRepository.save(new Orders(sellerId, id, id, eventStartAt, 777L, 10000));
        order.markPaid();
        return order;
    }

    private Orders paidOrder() {
        return paidOrder(LocalDateTime.now().plusDays(3));
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void acceptingMovesTheTicketToTheRecipient() {
        Orders order = paidOrder();

        TransferResponse created = transfers.create(sellerId, order.getId(), "buyer@example.com");
        assertThat(created.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(created.direction()).isEqualTo("OUTGOING");
        assertThat(created.counterpart()).isEqualTo("bu***@example.com");

        transfers.accept(buyerId, created.transferId());

        assertThat(order.holderId()).isEqualTo(buyerId);
        assertThat(order.getUserId()).isEqualTo(sellerId); // 결제 기록은 구매자에게 남는다
        assertThat(orderInternalService.hasPaidOrder(buyerId, 777L)).isTrue();
        assertThat(orderInternalService.hasPaidOrder(sellerId, 777L)).isFalse();
    }

    @Test
    void ticketsFollowTheHolder() {
        Orders order = paidOrder();
        ticketRepository.save(new Ticket(order, UUID.randomUUID().toString()));
        em.flush();
        assertThat(ticketRepository.findByHolderOrderByIssuedAtDesc(sellerId)).hasSize(1);
        assertThat(ticketRepository.findByHolderOrderByIssuedAtDesc(buyerId)).isEmpty();

        // QR 티켓이 생기면 시계가 뭐라고 하든 창은 닫힌다.
        expectError(() -> transfers.create(sellerId, order.getId(), "buyer@example.com"), ErrorCode.TRANSFER_CLOSED);

        order.transferTo(buyerId);
        em.flush();
        assertThat(ticketRepository.findByHolderOrderByIssuedAtDesc(sellerId)).isEmpty();
        assertThat(ticketRepository.findByHolderOrderByIssuedAtDesc(buyerId)).hasSize(1);
    }

    @Test
    void listShowsBothSidesAndReportsAClosedWindowAsExpired() {
        Orders order = paidOrder();
        TransferResponse created = transfers.create(sellerId, order.getId(), "buyer@example.com");

        assertThat(transfers.list(sellerId)).extracting(TransferResponse::direction).containsExactly("OUTGOING");
        List<TransferResponse> incoming = transfers.list(buyerId);
        assertThat(incoming).extracting(TransferResponse::direction).containsExactly("INCOMING");
        assertThat(incoming.get(0).counterpart()).isEqualTo("보내는사람");
        assertThat(incoming.get(0).status()).isEqualTo(TransferStatus.PENDING);

        em.flush();
        em.createQuery("update Orders o set o.eventStartAt = :t where o.id = :id")
                .setParameter("t", LocalDateTime.now().plusMinutes(30))
                .setParameter("id", order.getId())
                .executeUpdate();
        em.clear();
        assertThat(transfers.list(buyerId).get(0).status()).isEqualTo(TransferStatus.EXPIRED);
        expectError(() -> transfers.accept(buyerId, created.transferId()), ErrorCode.TRANSFER_CLOSED);
        em.clear(); // 닫는 것은 대량 UPDATE였다. 캐시된 엔티티를 버려서 읽기가 DB에 닿게 한다
        assertThat(transferRepository.findById(created.transferId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.EXPIRED);
    }

    @Test
    void onlyOnePendingTransferPerOrder() {
        Orders order = paidOrder();
        transfers.create(sellerId, order.getId(), "buyer@example.com");
        expectError(() -> transfers.create(sellerId, order.getId(), "buyer@example.com"), ErrorCode.TRANSFER_ALREADY_PENDING);
    }

    @Test
    void aTicketCanOnlyBeTransferredOnce() {
        Orders order = paidOrder();
        transfers.accept(buyerId, transfers.create(sellerId, order.getId(), "buyer@example.com").transferId());

        // 받은 사람은 이제 소지자지만 다시 넘길 수는 없고, 구매자는 더는 소지자가 아니다.
        when(authServiceClient.lookupByEmail("third@example.com"))
                .thenReturn(new UserInternalResponse(buyerId + 1, "third@example.com", "제삼자", true));
        expectError(() -> transfers.create(buyerId, order.getId(), "third@example.com"), ErrorCode.TRANSFER_NOT_ALLOWED);
        expectError(() -> transfers.create(sellerId, order.getId(), "third@example.com"), ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsInvalidRequests() {
        Orders order = paidOrder();
        expectError(() -> transfers.create(sellerId, order.getId(), "seller@example.com"), ErrorCode.TRANSFER_TO_SELF);
        expectError(() -> transfers.create(sellerId, order.getId(), "nobody@example.com"), ErrorCode.TRANSFER_RECIPIENT_NOT_FOUND);
        expectError(() -> transfers.create(buyerId, order.getId(), "seller@example.com"), ErrorCode.FORBIDDEN);

        Orders unpaid = orderRepository.save(new Orders(sellerId, SEQ.incrementAndGet(), SEQ.incrementAndGet(),
                LocalDateTime.now().plusDays(3), 777L, 10000));
        expectError(() -> transfers.create(sellerId, unpaid.getId(), "buyer@example.com"), ErrorCode.TRANSFER_NOT_ALLOWED);

        Orders tooLate = paidOrder(LocalDateTime.now().plusHours(1));
        expectError(() -> transfers.create(sellerId, tooLate.getId(), "buyer@example.com"), ErrorCode.TRANSFER_CLOSED);

    }

    @Test
    void declineAndCancelEndTheRequestAndFreeTheOrder() {
        Orders order = paidOrder();

        TransferResponse first = transfers.create(sellerId, order.getId(), "buyer@example.com");
        transfers.decline(buyerId, first.transferId());
        em.clear(); // 닫는 것은 대량 UPDATE였다. 캐시된 엔티티를 버려서 읽기가 DB에 닿게 한다
        assertThat(transferRepository.findById(first.transferId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.DECLINED);

        TransferResponse second = transfers.create(sellerId, order.getId(), "buyer@example.com"); // 다시 시도해도 된다
        transfers.cancel(sellerId, second.transferId());
        em.clear(); // 닫는 것은 대량 UPDATE였다. 캐시된 엔티티를 버려서 읽기가 DB에 닿게 한다
        assertThat(transferRepository.findById(second.transferId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.CANCELLED);

        expectError(() -> transfers.accept(buyerId, second.transferId()), ErrorCode.TRANSFER_NOT_PENDING);
        assertThat(order.wasTransferred()).isFalse();
    }

    @Test
    void onlyThePartiesCanAnswer() {
        Orders order = paidOrder();
        TransferResponse created = transfers.create(sellerId, order.getId(), "buyer@example.com");

        expectError(() -> transfers.accept(sellerId, created.transferId()), ErrorCode.TRANSFER_NOT_FOUND);
        expectError(() -> transfers.decline(sellerId, created.transferId()), ErrorCode.TRANSFER_NOT_FOUND);
        expectError(() -> transfers.cancel(buyerId, created.transferId()), ErrorCode.TRANSFER_NOT_FOUND);
        expectError(() -> transfers.accept(buyerId + 5, created.transferId()), ErrorCode.TRANSFER_NOT_FOUND);
    }

    @Test
    void transferredOrPendingTicketsCannotBeCancelled() {
        Orders order = paidOrder();
        TransferResponse created = transfers.create(sellerId, order.getId(), "buyer@example.com");
        expectError(() -> orderService.refundPreview(sellerId, order.getId()), ErrorCode.ORDER_TRANSFER_PENDING);

        transfers.accept(buyerId, created.transferId());
        expectError(() -> orderService.refundPreview(sellerId, order.getId()), ErrorCode.ORDER_TRANSFERRED);
    }

    @Test
    void masksTheRecipientEmail() {
        assertThat(TransferService.maskEmail("gildong@example.com")).isEqualTo("gi***@example.com");
        assertThat(TransferService.maskEmail("ab@example.com")).isEqualTo("a***@example.com");
        assertThat(TransferService.maskEmail("a@example.com")).isEqualTo("a***@example.com");
        assertThat(TransferService.maskEmail("no-at-sign")).isEqualTo("***");
    }
}
