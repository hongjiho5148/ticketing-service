package com.ticketing.backend.queue;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 모든 브라우저가 2초마다 "아직 입장 못 했나요?"를 묻는 대신 기다리는 사람들에게 대기열 업데이트를 밀어 준다.
 *
 * <p>기다리는 클라이언트는 각자 backend 인스턴스 하나에 SSE 연결 하나를 유지한다. 누가 입장하는지는 스윕 잠금을 얻은
 * 인스턴스(QueueAdmissionSweeper)가 정하고, 그 인스턴스가 특정 연결을 쥔 인스턴스인 경우는 드물다. 그래서 스위퍼는
 * Redis 토픽에 "이 토큰들이 입장했다"를 발행하고, 모든 인스턴스가 자기가 쥔 연결에 전달한다. 기다리는 사람 앞의 누구도
 * 입장하는 것 말고는 빠질 수 없고 입장은 항상 줄의 맨 앞을 가져가므로, 기다리는 사람의 순번은 묶음의 크기만큼 정확히
 * 줄어든다. 그래서 연결마다 Redis에 묻지 않고 산술로 순번을 갱신한다.
 *
 * <p>Pub/sub은 쏘고 잊는 방식이라 메시지를 잃어도 안전하게 해 주는 것이 두 가지 있다. 주기적으로 모든 연결의 실제 상태를
 * Redis에서 다시 읽고, 클라이언트는 스트림이 입장 없이 끝나면 폴링으로 되돌아간다.
 */
@Component
public class QueueStreams {

    private static final Logger log = LoggerFactory.getLogger(QueueStreams.class);
    private static final String ADMITTED_TOPIC = "queue:admitted";

    private final QueueService queueService;
    private final RedissonClient redissonClient;
    private final long timeoutMs;
    private final int maxConnections;
    private final Counter statusPushes;
    private final Counter passedPushes;
    private final Counter rejected;
    private final Timer reconcileTimer;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    public QueueStreams(
            QueueService queueService,
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            @Value("${queue.stream.timeout-ms:3600000}") long timeoutMs,
            @Value("${queue.stream.max-connections:5000}") int maxConnections) {
        this.queueService = queueService;
        this.redissonClient = redissonClient;
        this.timeoutMs = timeoutMs;
        this.maxConnections = maxConnections;
        this.statusPushes = Counter.builder("queue_stream_pushes_total").tag("type", "status")
                .description("Updates pushed to waiting clients over SSE").register(meterRegistry);
        this.passedPushes = Counter.builder("queue_stream_pushes_total").tag("type", "passed")
                .description("Updates pushed to waiting clients over SSE").register(meterRegistry);
        this.rejected = Counter.builder("queue_stream_rejected_total")
                .description("Stream requests turned away because this instance was full").register(meterRegistry);
        this.reconcileTimer = Timer.builder("queue_stream_reconcile_seconds")
                .description("How long one pass of re-reading every open connection's state from Redis takes").register(meterRegistry);
        Gauge.builder("queue_stream_connections", streams, Map::size)
                .description("Open queue SSE connections on this instance").register(meterRegistry);
    }

    @PostConstruct
    void subscribe() {
        RTopic topic = redissonClient.getTopic(ADMITTED_TOPIC, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, message) -> {
            try {
                onAdmitted(message);
            } catch (RuntimeException e) {
                log.warn("could not apply an admission message", e);
            }
        });
    }

    /** 토큰에 대한 SSE 연결을 연다. 모르는 토큰이면 status()처럼 예외를 던진다. */
    public SseEmitter open(String queueToken) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        QueueStreamSink sink = new QueueStreamSink() {
            @Override
            public void send(String event, Object data) throws IOException {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            }

            @Override
            public void heartbeat() throws IOException {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        };
        register(queueToken, sink);
        emitter.onCompletion(() -> remove(queueToken));
        emitter.onTimeout(() -> remove(queueToken));
        emitter.onError(e -> remove(queueToken));
        return emitter;
    }

    void register(String queueToken, QueueStreamSink sink) {
        if (streams.size() >= maxConnections) {
            rejected.increment();
            throw new ApiException(ErrorCode.QUEUE_STREAM_FULL);
        }
        Long eventId = queueService.eventOf(queueToken); // 모르는 토큰이면 QUEUE_TOKEN_NOT_FOUND를 던진다
        // 상태를 읽기 전에 등록한다. 그 사이에 알려진 입장은 이 스트림에 적용되거나 읽은 값에 이미 보이므로 놓치는 일이 없다.
        Stream stream = new Stream(queueToken, eventId, sink);
        streams.put(queueToken, stream);
        QueueStatusResponse status;
        try {
            status = queueService.status(queueToken);
        } catch (RuntimeException e) {
            streams.remove(queueToken);
            throw e;
        }
        if ("WAITING".equals(status.status())) {
            stream.rank = status.rankNo();
        }
        push(stream, status);
    }

    /** 스윕을 돌린 인스턴스에서 호출한다. Redis를 거쳐 모든 인스턴스의 연결에 닿는다. */
    public void announceAdmitted(Long eventId, long passExpiresAt, java.util.Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        redissonClient.getTopic(ADMITTED_TOPIC, StringCodec.INSTANCE)
                .publish(eventId + ":" + passExpiresAt + ":" + String.join(",", tokens));
    }

    void onAdmitted(String message) {
        int first = message.indexOf(':');
        int second = message.indexOf(':', first + 1);
        Long eventId = Long.valueOf(message.substring(0, first));
        long passExpiresAt = Long.parseLong(message.substring(first + 1, second));
        List<String> tokens = Arrays.asList(message.substring(second + 1).split(","));
        for (String token : tokens) {
            Stream stream = streams.get(token);
            if (stream != null) {
                push(stream, QueueStatusResponse.passed(token, QueueService.secondsUntil(passExpiresAt)));
            }
        }
        for (Stream stream : streams.values()) {
            if (eventId.equals(stream.eventId)) {
                stream.rank = Math.max(1, stream.rank - tokens.size());
                stream.dirty = true;
            }
        }
    }

    /** 자리가 움직인 모든 사람에게 새 순번을 보낸다. 묶음이 얼마나 자주 입장하든 초당 한 번 이하다. */
    @Scheduled(initialDelayString = "${queue.stream.flush-interval-ms:1000}", fixedDelayString = "${queue.stream.flush-interval-ms:1000}")
    public void flushRanks() {
        for (Stream stream : new ArrayList<>(streams.values())) {
            if (stream.dirty) {
                stream.dirty = false;
                pushRank(stream);
            }
        }
    }

    /** 유실된 메시지와 어긋난 순번을 위한 보루다. 각 연결의 실제 상태를 Redis에서 다시 읽는다. */
    @Scheduled(initialDelayString = "${queue.stream.reconcile-interval-ms:10000}", fixedDelayString = "${queue.stream.reconcile-interval-ms:10000}")
    public void reconcile() {
        reconcileTimer.record(this::reconcileAll);
    }

    private void reconcileAll() {
        List<Stream> open = new ArrayList<>(streams.values());
        if (open.isEmpty()) {
            return;
        }
        Map<String, Long> eventByToken = new java.util.HashMap<>();
        for (Stream stream : open) {
            eventByToken.put(stream.queueToken, stream.eventId);
        }
        Map<String, QueueStatusResponse> states;
        try {
            states = queueService.statusOfMany(eventByToken);
        } catch (RuntimeException e) {
            log.warn("could not refresh queue streams", e);
            return;
        }
        for (Stream stream : open) {
            QueueStatusResponse status = states.get(stream.queueToken);
            if (status == null) {
                // 대기열에서 없어졌다(만료됐거나 빠졌다). 클라이언트가 물어서 알게 된다
                streams.remove(stream.queueToken);
                stream.sink.complete();
            } else if ("WAITING".equals(status.status())) {
                stream.rank = status.rankNo();
                pushRank(stream);
            } else {
                push(stream, status);
            }
        }
    }

    /**
     * 연결된 이 사람들이 아직 여기 있다고 줄에 알린다. 이 인스턴스가 쥔 연결이 몇 개든 공연당 쓰기 한 번이다. 죽은 연결은
     * 하트비트가 떨어뜨리고 더는 갱신되지 않으며, 떠난 사람은 그렇게 알아챈다(QueueAdmissionSweeper.removeAbandoned).
     */
    @Scheduled(initialDelayString = "${queue.stream.seen-interval-ms:5000}", fixedDelayString = "${queue.stream.seen-interval-ms:5000}")
    public void refreshSeen() {
        Map<Long, List<String>> byEvent = new java.util.HashMap<>();
        for (Stream stream : streams.values()) {
            byEvent.computeIfAbsent(stream.eventId, k -> new ArrayList<>()).add(stream.queueToken);
        }
        byEvent.forEach((eventId, tokens) -> {
            try {
                queueService.touch(eventId, tokens);
            } catch (RuntimeException e) {
                log.warn("could not refresh who is still here for event {}", eventId, e);
            }
        });
    }

    @Scheduled(initialDelayString = "${queue.stream.heartbeat-interval-ms:15000}", fixedDelayString = "${queue.stream.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        for (Stream stream : new ArrayList<>(streams.values())) {
            try {
                stream.sink.heartbeat();
            } catch (IOException | RuntimeException e) {
                drop(stream);
            }
        }
    }

    void remove(String queueToken) {
        streams.remove(queueToken);
    }

    int connections() {
        return streams.size();
    }

    /** 이미 그 숫자를 가진 경우가 아니면 클라이언트에게 순번을 알린다. */
    private void pushRank(Stream stream) {
        long rank = stream.rank;
        if (rank != stream.lastSentRank) {
            push(stream, QueueStatusResponse.waiting(rank, queueService.estimateWaitSeconds(rank)));
        }
    }

    private void push(Stream stream, QueueStatusResponse status) {
        try {
            stream.sink.send("status", status);
            stream.lastSentRank = status.rankNo();
            if ("PASSED".equals(status.status())) {
                passedPushes.increment();
                streams.remove(stream.queueToken);
                stream.sink.complete();
            } else {
                statusPushes.increment();
            }
        } catch (IOException | RuntimeException e) {
            drop(stream); // 클라이언트가 떠났다
        }
    }

    private void drop(Stream stream) {
        streams.remove(stream.queueToken);
        try {
            stream.sink.complete();
        } catch (RuntimeException ignored) {
            // 이미 닫혔다
        }
    }

    private static final class Stream {
        final String queueToken;
        final Long eventId;
        final QueueStreamSink sink;
        volatile long rank;
        volatile long lastSentRank = -1;
        volatile boolean dirty;

        Stream(String queueToken, Long eventId, QueueStreamSink sink) {
            this.queueToken = queueToken;
            this.eventId = eventId;
            this.sink = sink;
        }
    }
}
