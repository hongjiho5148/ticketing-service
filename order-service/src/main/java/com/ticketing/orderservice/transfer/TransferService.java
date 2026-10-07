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
 * Hand a paid ticket to another member: the holder names a recipient by email, the recipient
 * accepts or declines, and acceptance flips Orders.ownerId. Payment, refund and benefit bookkeeping
 * stay with the buyer, which is why a transferred or pending ticket can no longer be cancelled.
 * noRollbackFor keeps the "this window closed" bookkeeping when we then refuse the request.
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
        // Same row lock cancelOrder takes: a cancel and a transfer on one order can't both get through.
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
            // Settle it as expired (kept thanks to noRollbackFor) so it stops looking answerable.
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

    /** Loads a transfer the caller is a party to - as the recipient, or as the sender. Strangers just get "not found". */
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

    // Only once the transfer is really committed - a rolled-back request must not have mailed anyone.
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

    /** "gildong@example.com" -> "gi***@example.com": enough to recognise the recipient, not enough to harvest an address. */
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
