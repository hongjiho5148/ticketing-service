package com.ticketing.reservationservice.messaging;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OutboxRecorder가 저장한 것을 발행한다. DB 트랜잭션 밖에서 돈다. 브로커는 네트워크 호출이고, 그것을 감싼
 * 트랜잭션은 기다리는 동안 풀의 커넥션을 붙잡는다(예약 경로가 피하는 것과 같은 함정이다).
 *
 * <p>폴링마다 먼저 짧은 임대로 전송 전 행 묶음을 <b>가져간다</b>(원자적 UPDATE 한 번). 그래서 여러 인스턴스가 -
 * 또는 폴링과 같은 순간에 도는 {@link #kick()}이 - 같은 행을 두 번 보내지 않는다. 가져간 쪽이 전송 도중 죽으면
 * 임대가 끝나고 다른 폴링이 그 행을 넘겨받는다.
 *
 * <p>전달은 여전히 최소 한 번(at-least-once)이다. 브로커가 확인한 뒤에야 행을 전송 완료로 표시하므로, 둘 사이에서
 * 죽거나(또는 느린 전송이 진행 중인데 임대가 끝나면) 다시 보내질 수 있다. 소비자는 중복을 견뎌야 한다. 한 폴링
 * 안에서는 순서가 유지된다. 첫 실패가 묶음을 멈추고 나머지를 돌려주므로 뒤의 이벤트가 앞의 이벤트를 앞지르지 않는다.
 * 인스턴스 사이에서는 다른 폴링이 가져간 행이 어느 순서로든 나갈 수 있는데, 키를 공유하는 이벤트에만 문제가 된다
 * (지금은 모든 이벤트가 자기 키를 가진다). 한 인스턴스 안의 여러 일꾼(outbox.relay-workers)도 같은 성질이다.
 *
 * <p>예약된 폴링({@link #relay()})은 밀려 있는 동안 쉬지 않고 이어서 폴링한다({@link #drain()}). 폴링 한 번
 * ({@link #relayOnce()})의 동작은 그대로다.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final OutboxClaimer claimer;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;
    private final long retentionHours;
    private final long leaseMs;
    private final String topicPattern;
    private final AtomicLong lastWarnAtMs = new AtomicLong();
    private final Counter sentCounter;
    private final Counter failureCounter;
    private final int workers;
    private volatile boolean stopping;

    /** 돌고 있는 폴링 뒤에서 기다리는 추가 폴링은 최대 하나다. 그 폴링이 모든 것을 보므로 더 많은 요청은 버린다. */
    private final ExecutorService kicker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());

    /** 일꾼이 둘 이상일 때 예약된 폴링 안에서 나머지 일꾼들이 도는 스레드들(현재 스레드가 첫 번째 일꾼이라 workers - 1개). */
    private final ExecutorService workerPool;

    /** 폴링 한 번의 결과: 가져간 행 수와 그중 브로커가 확인한 수 */
    private record Poll(int claimed, int sent) {
        /** 꽉 찬 묶음을 하나도 빠짐없이 보냈다면 아직 밀려 있을 가능성이 크다. 비었거나 중간에 실패했으면 쉬어야 한다. */
        boolean hasMore() {
            return claimed >= OutboxEventRepository.CLAIM_BATCH && sent == claimed;
        }
    }

    public OutboxRelay(
            OutboxEventRepository repository,
            OutboxClaimer claimer,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${outbox.send-timeout-ms:5000}") long sendTimeoutMs,
            @Value("${outbox.retention-hours:24}") long retentionHours,
            @Value("${outbox.lease-ms:30000}") long leaseMs,
            @Value("${outbox.topic-pattern:%}") String topicPattern,
            @Value("${outbox.relay-workers:1}") int workers,
            MeterRegistry meterRegistry) {
        this.workers = Math.max(1, workers);
        this.workerPool = this.workers > 1
                ? Executors.newFixedThreadPool(this.workers - 1, runnable -> {
                    Thread thread = new Thread(runnable, "outbox-relay-worker");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.sentCounter = Counter.builder("outbox_events_sent_total")
                .description("Outbox events acknowledged by Kafka").register(meterRegistry);
        this.failureCounter = Counter.builder("outbox_send_failures_total")
                .description("Attempts to send an outbox event that Kafka did not acknowledge").register(meterRegistry);
        this.repository = repository;
        this.claimer = claimer;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
        this.retentionHours = retentionHours;
        this.leaseMs = leaseMs;
        this.topicPattern = topicPattern;
    }

    @Scheduled(
            fixedDelayString = "${outbox.relay-interval-ms:500}",
            initialDelayString = "${outbox.relay-initial-delay-ms:1000}")
    public void relay() {
        drain();
    }

    /**
     * 밀려 있는 동안 쉬지 않고 이어서 폴링한다. 예전에는 폴링 한 번(최대 100개)이 끝나면 매번 폴링 간격(0.5초)만큼 쉬었고,
     * 이벤트를 하나씩 보내고 확인을 기다리는 시간이 길 때(측정: 이벤트당 약 10~22ms, 그중 약 90%가 Kafka 확인 대기)
     * 전체 시간의 20~40%가 그냥 쉬는 시간이었다. 이벤트가 만들어지는 속도가 이를 넘으면 계속 밀렸다.
     *
     * <p>일꾼이 둘 이상이면(outbox.relay-workers) 같은 일을 동시에 한다. 폴링 한 번 안의 순서 규칙(앞의 이벤트가 실패하면 뒤의 것은
     * 보내지 않는다)은 그대로이고, 일꾼들이 서로 다른 행을 가져가므로(SKIP LOCKED) 같은 행을 두 번 보내지도 않는다. 일꾼 사이에서는
     * 폴링이 겹치므로 서로 다른 묶음의 전송 순서는 보장하지 않는다. kick()이 예약된 폴링과 동시에 도는 것과 같은 성질이다.
     */
    void drain() {
        List<Future<?>> others = new ArrayList<>();
        for (int i = 1; i < workers; i++) {
            others.add(workerPool.submit(this::drainLoop));
        }
        drainLoop();
        for (Future<?> other : others) {
            try {
                other.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                log.warn("Outbox: relay worker stopped unexpectedly: {}", String.valueOf(e.getCause()));
            }
        }
    }

    private void drainLoop() {
        while (!stopping) {
            Poll poll;
            try {
                poll = poll();
            } catch (RuntimeException e) {
                log.warn("Outbox: poll failed, will retry on the next tick: {}", e.toString());
                return;
            }
            if (!poll.hasMore()) {
                return;
            }
        }
    }

    /**
     * 다음 주기를 기다리지 않고 지금 폴링해 달라고 요청한다. 이벤트가 커밋된 뒤에 호출해서 몇 밀리초 안에 나가게 한다.
     * 바로 돌아오며 폴링은 자기 스레드에서 돈다. 예약된 폴링은 이것이 놓친 것(실패한 전송, 재시작,
     * 다른 인스턴스가 기록한 이벤트)을 위한 안전망으로 남는다.
     */
    public void kick() {
        kicker.execute(this::relayOnce);
    }

    @PreDestroy
    void stopKicker() {
        stopping = true;
        kicker.shutdown();
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    /** 전송 전 이벤트 한 묶음을 순서대로 보내고, 브로커에 닿은 개수를 돌려준다. */
    public int relayOnce() {
        return poll().sent();
    }

    private Poll poll() {
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        int claimed = claimer.claim(token, now, now.plus(Duration.ofMillis(leaseMs)), topicPattern);
        if (claimed == 0) {
            return new Poll(0, 0);
        }
        List<OutboxEvent> batch = repository.findByClaimedByAndSentAtIsNullOrderByIdAsc(token);
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate
                        .send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                repository.release(token);
                break;
            } catch (Exception e) {
                // Kafka가 죽었거나 느리다. 잃는 것은 없다. 행은 전송 전으로 돌아가고 다음 폴링이 여기서부터 시작한다.
                repository.release(token);
                failureCounter.increment();
                warnThrottled(batch.get(0), batch.size() - sent, e);
                break;
            }
            repository.markSent(event.getId(), LocalDateTime.now());
            sentCounter.increment();
            sent++;
        }
        return new Poll(claimed, sent);
    }

    /**
     * 폴링은 초당 두 번 돈다. 장애 때 경고를 초당 두 번 쓰지 않도록 30초에 한 번이면 충분하다.
     * 가장 오래 기다린 이벤트가 얼마나 막혀 있는지를 알려 준다. 알림을 걸어야 할 숫자다.
     */
    private void warnThrottled(OutboxEvent oldest, int waiting, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAtMs.get();
        // 일꾼이 여럿이라 같은 순간에 여럿이 경고하려 할 수 있다. 시각을 먼저 바꾼 하나만 쓴다.
        if (now - last >= 30_000 && lastWarnAtMs.compareAndSet(last, now)) {
            long waitingSeconds = Duration.between(oldest.getCreatedAt(), LocalDateTime.now()).toSeconds();
            log.warn("Outbox: {} events waiting, the oldest (id {}, {}) for {}s - not delivered, retrying every poll: {}",
                    waiting, oldest.getId(), oldest.getTopic(), waitingSeconds, e.getMessage());
        }
    }

    /** 전송 완료 행은 한동안만 둔다(무엇이 나갔는지 보기 위해). 전송 전 행은 절대 지우지 않는다. */
    @Scheduled(cron = "${outbox.purge-cron:0 0 * * * *}")
    public void purgeSent() {
        int deleted = repository.deleteSentBefore(LocalDateTime.now().minusHours(retentionHours));
        if (deleted > 0) {
            log.info("Purged {} sent outbox events", deleted);
        }
    }
}
