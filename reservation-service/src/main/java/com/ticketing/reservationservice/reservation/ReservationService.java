package com.ticketing.reservationservice.reservation;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.messaging.ReservationCreatedEvent;
import com.ticketing.reservationservice.messaging.KafkaTopics;
import com.ticketing.reservationservice.messaging.OutboxRecorder;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import com.ticketing.reservationservice.reservation.dto.ReservationResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전체를 @Transactional로 하지 않은 것은 일부러다. reserve()와 cancel()은 event-service와 대기열을 HTTP로
 * 호출하는데, 그 호출을 감싼 트랜잭션은 스레드가 네트워크만 기다리는 동안 DB 커넥션을 붙잡는다. 풀이 작으면
 * (Hikari 기본값은 10) 동시 예약이 대략 풀 크기로 제한되고 나머지는 커넥션을 기다리며 줄을 서게 된다. 그래서
 * 아래의 리포지토리 호출은 각각 자기 짧은 트랜잭션에서 실행되고, 커넥션은 실제로 쓰는 동안에만 빌린다.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final QueueServiceClient queueServiceClient;
    private final OutboxRecorder outboxRecorder;
    private final TransactionTemplate transactionTemplate;
    private final int maxSeatsPerEvent;

    public ReservationService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            QueueServiceClient queueServiceClient,
            OutboxRecorder outboxRecorder,
            PlatformTransactionManager transactionManager,
            @Value("${reservation.max-seats-per-event}") int maxSeatsPerEvent) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.queueServiceClient = queueServiceClient;
        this.outboxRecorder = outboxRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxSeatsPerEvent = maxSeatsPerEvent;
    }

    public ReservationResponse reserve(Long userId, ReservationCreateRequest request, String passToken) {
        SeatDetailResponse requestedSeat = eventServiceClient.getSeat(request.seatId());
        if (requestedSeat.salesClosed()) {
            // event-service가 자기 시계로 판정한다. 좌석은 공연 시작 2시간 전까지 잡을 수 있다
            throw new ApiException(ErrorCode.SALES_CLOSED);
        }
        if (!queueServiceClient.isPassTokenValid(requestedSeat.eventId(), passToken)) {
            throw new ApiException(ErrorCode.PASS_TOKEN_REQUIRED);
        }
        if (reservationRepository.countActiveSeats(userId, requestedSeat.eventId(), LocalDateTime.now())
                >= maxSeatsPerEvent) {
            throw new ApiException(ErrorCode.SEAT_LIMIT_EXCEEDED);
        }

        // event-service가 예전에 여기서 로컬로 감싸던 것과 같은 좌석별 Redis 잠금을 쥐고, 자기 쪽에서
        // 상태 확인과 홀드를 원자적으로 한다. EventServiceClient.hold() 참고.
        LocalDateTime holdExpireAt = LocalDateTime.now().plus(HOLD_DURATION);
        SeatDetailResponse heldSeat = eventServiceClient.hold(request.seatId(), holdExpireAt);

        Reservation reservation = new Reservation(userId, request.seatId(), heldSeat.eventId(), holdExpireAt);
        // 예약과 그것을 알리는 이벤트를 짧은 트랜잭션 하나로 저장한다. 둘 다 있거나 둘 다 없으므로
        // 이벤트가 사라지거나(또는 저장되지 않은 예약을 알리거나) 하지 않는다. OutboxRelay가 그 뒤에 Kafka로
        // 보낸다. 브로커는 이 요청의 일부가 아니다.
        Reservation saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Reservation created = reservationRepository.save(reservation);
                outboxRecorder.record(
                        KafkaTopics.RESERVATION_CREATED,
                        created.getId().toString(),
                        new ReservationCreatedEvent(
                                created.getId(),
                                heldSeat.seatId(),
                                userId,
                                heldSeat.eventId(),
                                created.getCreatedAt().toString()));
                return created;
            });
        } catch (RuntimeException e) {
            // 좌석은 이미 event-service에서 잡혀 있다. 예약 행이 없으면 아무도 그것을 취소하거나 결제할 수 없으므로,
            // 홀드가 끝날 때까지 막아 두지 않고 지금 돌려준다.
            releaseQuietly(request.seatId(), holdExpireAt);
            throw e;
        }

        return ReservationResponse.from(saved);
    }

    public void cancel(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        // 먼저 풀고 그다음에 취소를 기록한다. 풀기가 실패하면 아무것도 바뀌지 않았으니 사용자가 다시 시도할 수 있고,
        // 그 뒤에 저장이 실패하면 재시도 때 좌석이 이미 풀려 있다(해제는 멱등하다).
        eventServiceClient.release(reservation.getSeatId(), reservation.getHoldExpireAt());
        reservation.cancel();
        reservationRepository.save(reservation);
    }

    private void releaseQuietly(Long seatId, LocalDateTime holdExpireAt) {
        try {
            eventServiceClient.release(seatId, holdExpireAt);
        } catch (RuntimeException e) {
            // 홀드 만료 스위퍼가 어차피 몇 분 안에 풀어 준다.
            log.warn("Could not release seat {} after a failed reservation save: {}", seatId, e.getMessage());
        }
    }
}
