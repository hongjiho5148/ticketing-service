package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.authclient.AuthServiceClient;
import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.ticket.TicketRepository;
import com.ticketing.orderservice.transfer.dto.TransferResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;

/**
 * 결제된 티켓을 다른 회원에게 넘긴다. 소지자가 이메일로 받는 사람을 지정하고, 받는 사람이 수락하거나
 * 거절하며, 수락하면 Orders.ownerId가 바뀐다. 결제, 환불, 혜택 기록은 구매자에게 남아 있으므로
 * 양도됐거나 양도 대기 중인 티켓은 더는 취소할 수 없다. noRollbackFor는 요청을 거절할 때도
 * "이 창이 닫혔다"는 기록을 남기게 한다.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class TransferService {

    private static final int LIST_LIMIT = 50;

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final TicketTransferRepository transferRepository;
    private final TransferPolicy policy;
    private final AuthServiceClient authServiceClient;
    private final EventServiceClient eventServiceClient;
    private final TransferNotifier notifier;

    public TransferService(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            TicketTransferRepository transferRepository,
            TransferPolicy policy,
            AuthServiceClient authServiceClient,
            EventServiceClient eventServiceClient,
            TransferNotifier notifier) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.transferRepository = transferRepository;
        this.policy = policy;
        this.authServiceClient = authServiceClient;
        this.eventServiceClient = eventServiceClient;
        this.notifier = notifier;
    }

    public TransferResponse create(Long userId, Long orderId, String email) {
        // cancelOrder가 잡는 것과 같은 행 잠금이다. 한 주문에 대한 취소와 양도가 둘 다 통과할 수는 없다.
        Orders order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.holderId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PAID || order.wasTransferred()) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_ALLOWED);
        }
        if (!policy.isOpen(order, LocalDateTime.now()) || ticketRepository.existsByOrderId(orderId)) {
            throw new ApiException(ErrorCode.TRANSFER_CLOSED);
        }
        if (transferRepository.existsByPendingOrderId(orderId)) {
            throw new ApiException(ErrorCode.TRANSFER_ALREADY_PENDING);
        }

        UserInternalResponse recipient = lookupRecipient(email);
        if (recipient.userId().equals(userId)) {
            throw new ApiException(ErrorCode.TRANSFER_TO_SELF);
        }
        UserInternalResponse sender = getUser(userId);

        TicketTransfer transfer;
        try {
            transfer = transferRepository.saveAndFlush(
                    new TicketTransfer(orderId, userId, recipient.userId(), sender.name(), maskEmail(recipient.email())));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.TRANSFER_ALREADY_PENDING);
        }

        SeatDetailResponse seat = eventServiceClient.getSeat(order.getSeatId());
        if (recipient.emailOptIn() && recipient.email() != null) {
            sendAfterCommit(recipient, sender.name(), seat);
        }
        return TransferResponse.from(userId, transfer, TransferStatus.PENDING, seat);
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> list(Long userId) {
        List<TicketTransfer> transfers = transferRepository.findInvolving(userId, PageRequest.of(0, LIST_LIMIT));
        if (transfers.isEmpty()) {
            return List.of();
        }
        Map<Long, Orders> orders = orderRepository
                .findAllById(transfers.stream().map(TicketTransfer::getOrderId).distinct().toList()).stream()
                .collect(Collectors.toMap(Orders::getId, Function.identity()));
        Map<Long, SeatDetailResponse> seats = eventServiceClient
                .getSeats(orders.values().stream().map(Orders::getSeatId).distinct().toList()).stream()
                .collect(Collectors.toMap(SeatDetailResponse::seatId, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        return transfers.stream()
                .map(t -> {
                    Orders order = orders.get(t.getOrderId());
                    TransferStatus status = t.getStatus() == TransferStatus.PENDING && isClosed(order, now)
                            ? TransferStatus.EXPIRED
                            : t.getStatus();
                    return TransferResponse.from(userId, t, status, seats.get(order.getSeatId()));
                })
                .toList();
    }

    public void accept(Long userId, Long transferId) {
        TicketTransfer transfer = loadFor(transferId, userId, true);
        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_PENDING);
        }
        Orders order = orderRepository.findByIdForUpdate(transfer.getOrderId())
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));

        if (isClosed(order, LocalDateTime.now())) {
            // 만료로 정리해서(noRollbackFor 덕분에 남는다) 더는 응답할 수 있는 것처럼 보이지 않게 한다.
            transferRepository.closeIfPending(transferId, TransferStatus.EXPIRED, LocalDateTime.now());
            throw new ApiException(ErrorCode.TRANSFER_CLOSED);
        }
        if (transferRepository.closeIfPending(transferId, TransferStatus.ACCEPTED, LocalDateTime.now()) == 0) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_PENDING);
        }
        order.transferTo(userId);
    }

    public void decline(Long userId, Long transferId) {
        loadFor(transferId, userId, true);
        close(transferId, TransferStatus.DECLINED);
    }

    public void cancel(Long userId, Long transferId) {
        loadFor(transferId, userId, false);
        close(transferId, TransferStatus.CANCELLED);
    }

    private void close(Long transferId, TransferStatus to) {
        if (transferRepository.closeIfPending(transferId, to, LocalDateTime.now()) == 0) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_PENDING);
        }
    }

    /** 호출자가 당사자인 양도를 불러온다. 받는 사람이거나 보낸 사람인 경우이고, 남에게는 그냥 "없음"이다. */
    private TicketTransfer loadFor(Long transferId, Long userId, boolean asRecipient) {
        TicketTransfer transfer =
                transferRepository.findById(transferId).orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_NOT_FOUND));
        Long expected = asRecipient ? transfer.getToUserId() : transfer.getFromUserId();
        if (!expected.equals(userId)) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_FOUND);
        }
        return transfer;
    }

    private boolean isClosed(Orders order, LocalDateTime now) {
        return order.getStatus() != OrderStatus.PAID
                || order.wasTransferred()
                || !policy.isOpen(order, now)
                || ticketRepository.existsByOrderId(order.getId());
    }

    private UserInternalResponse lookupRecipient(String email) {
        UserInternalResponse recipient;
        try {
            recipient = authServiceClient.lookupByEmail(email.trim());
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
        if (recipient == null) {
            throw new ApiException(ErrorCode.TRANSFER_RECIPIENT_NOT_FOUND);
        }
        return recipient;
    }

    private UserInternalResponse getUser(Long userId) {
        try {
            return authServiceClient.getUser(userId);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    // 양도가 실제로 커밋된 뒤에만 보낸다. 롤백된 요청이 누구에게 메일을 보내서는 안 된다.
    private void sendAfterCommit(UserInternalResponse recipient, String fromName, SeatDetailResponse seat) {
        Runnable send = () ->
                notifier.notifyRequested(recipient.email(), recipient.name(), fromName, seat.eventTitle(), seat.eventStartAt());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /** "gildong@example.com" -> "gi***@example.com": 받는 사람을 알아보기에는 충분하고 주소를 긁어모으기에는 부족하다. */
    static String maskEmail(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        int visible = at > 2 ? 2 : 1;
        return email.substring(0, visible) + "***" + email.substring(at);
    }
}
