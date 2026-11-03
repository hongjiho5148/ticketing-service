package com.ticketing.backend.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 입장한 사람들이 받은 슬롯으로 무엇을 하는지다. 입장하고 얼마 뒤에 첫 좌석을 잡는지, 입장한 사람 중 몇 명이나
 * 잡는지. 여기서는 누가 들어가는지를 바꾸지 않는다. 입장권이 얼마나 오래 유효해야 하는지에 대한 근거다(사람들이
 * 실제로 필요로 하는 시간보다 오래가거나 대부분 쓰지 않는 입장권은 다른 사람들을 기다리게 한다).
 *
 * <p>예약 생성 이벤트로 채운다. 입장 뒤 첫 예약만 센다. 그 사실은 Redis에 기억하므로, 좌석 네 개를 잡은
 * 사람도 한 번만 세고 다시 전달된 메시지도 두 번 세지 않는다.
 */
@Component
public class AdmissionUseTracker {

    private final WaitingQueueRepository waitingQueueRepository;
    private final RedissonClient redissonClient;
    private final Timer toFirstReservation;
    private final Counter admittedWhoReserved;

    public AdmissionUseTracker(WaitingQueueRepository waitingQueueRepository, RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.waitingQueueRepository = waitingQueueRepository;
        this.redissonClient = redissonClient;
        this.toFirstReservation = Timer.builder("queue_admission_to_first_reservation_seconds")
                .description("Time from being let in to holding the first seat")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(meterRegistry);
        this.admittedWhoReserved = Counter.builder("queue_admitted_reserved_total")
                .description("People let in from the queue who went on to hold a seat (counted once each)").register(meterRegistry);
    }

    /** @param createdAt 예약을 만든 시각. 이벤트가 담아 오는 대로 ISO-8601 로컬 날짜-시간이다 */
    public void onReservationCreated(Long userId, Long eventId, String createdAt) {
        Optional<WaitingQueue> admission = waitingQueueRepository.findByUserIdAndEventId(userId, eventId).stream()
                .filter(entry -> entry.getStatus() == QueueEntryStatus.PASSED && entry.getPassedAt() != null)
                .max(Comparator.comparing(WaitingQueue::getPassedAt));
        if (admission.isEmpty()) {
            return; // 대기열을 거치지 않았다(또는 이력을 남기기 전에 입장했다)
        }
        RBucket<Boolean> counted = redissonClient.getBucket("queue:first-reservation:" + admission.get().getQueueToken());
        if (!counted.trySet(true, 2, TimeUnit.HOURS)) {
            return; // 이 입장은 이미 센 것이다
        }
        Duration took = Duration.between(admission.get().getPassedAt(), LocalDateTime.parse(createdAt));
        toFirstReservation.record(took.isNegative() ? Duration.ZERO : took);
        admittedWhoReserved.increment();
    }
}
