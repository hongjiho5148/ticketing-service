package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketing.backend.auth.JwtTokenProvider;
import com.ticketing.backend.eventclient.EventServiceClient;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stream over HTTP, through the real security chain: it needs a login like everything else, it stays open (async)
 * until the person is let in, and when it ends the second, internal dispatch that Spring does is not turned into an
 * "Access Denied" error. Against the compose Redis and MySQL; run inside the compose network with DB_HOST=mysql
 * REDIS_HOST=redis.
 */
@SpringBootTest(properties = {
    "queue.admission-interval-ms=3600000",
    "queue.stream.flush-interval-ms=3600000",
    "queue.stream.reconcile-interval-ms=3600000",
    "queue.stream.heartbeat-interval-ms=3600000"
})
@AutoConfigureMockMvc
@Transactional
class QueueStreamHttpTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private MockMvc mockMvc;
    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 8_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private String queueToken;

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        if (queueToken != null) {
            redissonClient.getKeys().delete("queue:event:" + queueToken);
        }
    }

    private String login() {
        return "Bearer " + jwtTokenProvider.createAccessToken(9_200_000_000L + SEQ.incrementAndGet() % 1_000_000, "stream@example.com");
    }

    @Test
    void theStreamNeedsALogin() throws Exception {
        queueToken = queueService.enter(1L, eventId).queueToken();

        mockMvc.perform(get("/api/queue/stream").param("queueToken", queueToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void aStreamStaysOpenUntilThePersonIsLetInAndThenEndsCleanly() throws Exception {
        queueToken = queueService.enter(2L, eventId).queueToken();

        MvcResult started = mockMvc.perform(get("/api/queue/stream").param("queueToken", queueToken)
                        .header("Authorization", login()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Whoever runs the sweep announces the admission; the stream then pushes the pass and completes. (A sweeper from
        // another test's cached context may have let the person in already - either way the stream ends the same way.)
        sweeper.admitForEvent(eventId);
        started.getAsyncResult(5000);

        // Spring dispatches a finished async request once more; that pass has no login and must not become a 403.
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        assertThat(started.getResponse().getContentAsString()).contains("event:status").contains("PASSED").contains(queueToken);
    }
}
