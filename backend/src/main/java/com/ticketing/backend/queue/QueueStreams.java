package com.ticketing.backend.queue;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Pushes queue updates to the people waiting, instead of every browser asking "am I in yet?" every two seconds.
 *
 * <p>Each waiting client keeps one SSE connection to one backend instance. Who gets let in is decided by whichever
 * instance won the sweep lock (QueueAdmissionSweeper), and that is rarely the instance holding a given connection, so
 * the sweeper publishes "these tokens were admitted" on a Redis topic and every instance delivers to the connections it
 * holds. Nobody ahead of a waiter can be removed except by being admitted, and admission always takes the head of the
 * line, so a waiter's rank drops by exactly the size of the batch: ranks are updated by arithmetic, without asking Redis
 * for each connection.
 *
 * <p>Two things make this safe to lose messages (pub/sub is fire-and-forget): a periodic pass re-reads the true state of
 * every connection from Redis, and the client falls back to polling when the stream ends without a pass.
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

    /** Opens the SSE connection for a token. Throws like status() does for an unknown token. */
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
        Long eventId = queueService.eventOf(queueToken); // throws QUEUE_TOKEN_NOT_FOUND for an unknown token
        // Registered before the state is read: an admission announced in between is then either applied to this
        // stream or already visible in what is read, never missed.
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

    /** Called on the instance that ran the sweep; reaches every instance's connections through Redis. */
    public void announceAdmitted(Long eventId, java.util.Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        redissonClient.getTopic(ADMITTED_TOPIC, StringCodec.INSTANCE).publish(eventId + ":" + String.join(",", tokens));
    }

    void onAdmitted(String message) {
        int split = message.indexOf(':');
        Long eventId = Long.valueOf(message.substring(0, split));
        List<String> tokens = Arrays.asList(message.substring(split + 1).split(","));
        for (String token : tokens) {
            Stream stream = streams.get(token);
            if (stream != null) {
                push(stream, QueueStatusResponse.passed(token));
            }
        }
        for (Stream stream : streams.values()) {
            if (eventId.equals(stream.eventId)) {
                stream.rank = Math.max(1, stream.rank - tokens.size());
                stream.dirty = true;
            }
        }
    }

    /** Sends the new rank to everyone whose place moved, at most once a second however often batches are let in. */
    @Scheduled(initialDelayString = "${queue.stream.flush-interval-ms:1000}", fixedDelayString = "${queue.stream.flush-interval-ms:1000}")
    public void flushRanks() {
        for (Stream stream : new ArrayList<>(streams.values())) {
            if (stream.dirty) {
                stream.dirty = false;
                pushRank(stream);
            }
        }
    }

    /** The backstop for lost messages and ranks that drifted: re-reads each connection's real state from Redis. */
    @Scheduled(initialDelayString = "${queue.stream.reconcile-interval-ms:10000}", fixedDelayString = "${queue.stream.reconcile-interval-ms:10000}")
    public void reconcile() {
        for (Stream stream : new ArrayList<>(streams.values())) {
            try {
                QueueStatusResponse status = queueService.status(stream.queueToken);
                if ("WAITING".equals(status.status())) {
                    stream.rank = status.rankNo();
                    pushRank(stream);
                } else {
                    push(stream, status);
                }
            } catch (ApiException e) {
                streams.remove(stream.queueToken); // the token is gone (expired); the client will find out by asking
                stream.sink.complete();
            } catch (RuntimeException e) {
                log.warn("could not refresh queue stream", e);
            }
        }
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

    /** Tells the client its rank unless it already has that number. */
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
            drop(stream); // the client went away
        }
    }

    private void drop(Stream stream) {
        streams.remove(stream.queueToken);
        try {
            stream.sink.complete();
        } catch (RuntimeException ignored) {
            // already closed
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
