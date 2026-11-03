package com.ticketing.reservationservice.internal;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.internal.dto.ReservationDetailResponse;
import com.ticketing.reservationservice.messaging.KafkaTopics;
import com.ticketing.reservationservice.messaging.OutboxRecorder;
import com.ticketing.reservationservice.messaging.ReservationConfirmedEvent;
import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationRepository;
import com.ticketing.reservationservice.reservation.ReservationStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationInternalService {

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final OutboxRecorder outboxRecorder;

    public ReservationInternalService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            OutboxRecorder outboxRecorder) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.outboxRecorder = outboxRecorder;
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        return ReservationDetailResponse.from(reservation);
    }

    // order-service는 "예약 생명주기 -> 좌석 상태" 전환을 event-service를 직접 호출하지 않고 여기에 맡긴다.
    // 홀드가 끝났을 때 ReservationExpirySweeper가 이미 맡고 있는 책임과 같고, 다만 타임아웃이 아니라
    // 결제 성공이 이를 일으킨다.
    /**
     * 홀드가 아직 살아 있는 예약만 확정할 수 있다. 만료됐거나 취소된 예약이면 좌석이 이미 풀렸거나 다른 사람에게
     * 잡혔을 수 있으므로, order-service로 HOLD_EXPIRED를 돌려주고 그쪽이 결제를 환불한다.
     * 두 번 확정해도 괜찮다(일부가 실패한 뒤 다시 시도한 결제). 좌석 판매 자체도 홀드로 보호되므로, 스위퍼가
     * 아직 만료시키지 못한 예약이라도 그 사이 다시 잡힌 좌석을 팔 수는 없다.
     */
    public ReservationDetailResponse confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return ReservationDetailResponse.from(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED);
        }
        reservation.confirm();
        eventServiceClient.sell(reservation.getSeatId(), reservation.getHoldExpireAt());
        // 이 트랜잭션 안에서 기록하므로, 알림은 확정이 커밋될 때 정확히 그때 존재한다. 반복된
        // 확정은 위에서 돌아가고 아무것도 기록하지 않으므로 예약 하나당 이벤트는 하나다.
        outboxRecorder.record(
                KafkaTopics.RESERVATION_CONFIRMED,
                reservation.getId().toString(),
                new ReservationConfirmedEvent(
                        reservation.getId(),
                        reservation.getSeatId(),
                        reservation.getUserId(),
                        reservation.getEventId(),
                        LocalDateTime.now().toString()));
        return ReservationDetailResponse.from(reservation);
    }

    public ReservationDetailResponse cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        reservation.cancel();
        eventServiceClient.release(reservation.getSeatId(), reservation.getHoldExpireAt());
        return ReservationDetailResponse.from(reservation);
    }
}
