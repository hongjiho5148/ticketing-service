package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * 실제 보안 체인을 거치는 HTTP 위의 스트림이다. 다른 것처럼 로그인이 필요하고, 사람이 입장할 때까지 열려 있으며(비동기),
 * 끝날 때 Spring이 하는 두 번째 내부 디스패치가 "Access Denied" 오류로 바뀌지 않는다. compose Redis와 MySQL에서 실행하고,
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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
    void anUnknownTokenGetsAnErrorBodyNotAServerError() throws Exception {
        // 브라우저는 text/event-stream을 요청하는데, 그것에는 오류 본문을 위한 컨버터가 없다
        mockMvc.perform(get("/api/queue/stream").param("queueToken", "no-such-token")
                        .header("Authorization", login()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void aStreamStaysOpenUntilThePersonIsLetInAndThenEndsCleanly() throws Exception {
        queueToken = queueService.enter(2L, eventId).queueToken();

        MvcResult started = mockMvc.perform(get("/api/queue/stream").param("queueToken", queueToken)
                        .header("Authorization", login()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 스윕을 돌리는 쪽이 입장을 알리고, 스트림은 그러면 입장권을 밀어 주고 끝난다. (다른 테스트의 캐시된 컨텍스트에 있는
        // 스위퍼가 이미 그 사람을 입장시켰을 수도 있다. 어느 쪽이든 스트림은 똑같이 끝난다.)
        sweeper.admitForEvent(eventId);
        started.getAsyncResult(5000);

        // Spring은 끝난 비동기 요청을 한 번 더 디스패치한다. 그 통과에는 로그인이 없고 403이 되면 안 된다.
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        assertThat(started.getResponse().getContentAsString()).contains("event:status").contains("PASSED").contains(queueToken);
    }
}
