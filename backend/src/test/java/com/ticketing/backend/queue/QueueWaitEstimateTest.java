package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 내 앞에 몇 명이 있는지와 입장한 사람들의 입장권이 언제 끝나는지로 추정하는 대기시간이다. Spring도 Redis도 없이 계산식만
 * 확인한다. 슬롯 배열은 슬롯마다 비는 시각(밀리초)을 오름차순으로 담고, 이미 비어 있는 슬롯은 0이다.
 */
class QueueWaitEstimateTest {

    private static final long NOW = 1_000_000_000L;
    private static final long TTL_MS = 300_000;

    private static QueueService service(int capacity, long intervalMs, long passTtlMs) {
        return new QueueService(null, null, null, capacity, intervalMs, passTtlMs, true, 300_000, 120_000, new SimpleMeterRegistry());
    }

    private static long[] slots(long... freeInMs) {
        long[] result = new long[freeInMs.length];
        for (int i = 0; i < freeInMs.length; i++) {
            result[i] = freeInMs[i] == 0 ? 0 : NOW + freeInMs[i];
        }
        return result;
    }

    @Test
    void whoeverFitsInTheEmptySlotsGetsInOnTheNextTick() {
        QueueService queue = service(3, 300, TTL_MS);
        long[] empty = slots(0, 0, 0);

        assertThat(queue.estimateWaitSeconds(1, empty, NOW)).isEqualTo(1); // 주기 0.3초는 올림해서 1초
        assertThat(queue.estimateWaitSeconds(3, empty, NOW)).isEqualTo(1);
    }

    @Test
    void eachWaiterIsGivenTheSlotThatFreesUpFirst() {
        QueueService queue = service(3, 300, TTL_MS);
        long[] slots = slots(60_000, 120_000, 300_000); // 입장권이 1분, 2분, 5분 뒤에 끝난다

        assertThat(queue.estimateWaitSeconds(1, slots, NOW)).isEqualTo(60);
        assertThat(queue.estimateWaitSeconds(2, slots, NOW)).isEqualTo(120);
        assertThat(queue.estimateWaitSeconds(3, slots, NOW)).isEqualTo(300);
    }

    @Test
    void everyFullRoundOfCapacityAddsOnePassLifetime() {
        QueueService queue = service(3, 300, TTL_MS);
        long[] slots = slots(60_000, 120_000, 300_000);

        assertThat(queue.estimateWaitSeconds(4, slots, NOW)).isEqualTo(60 + 300); // 첫 슬롯이 다시 5분을 쥔다
        assertThat(queue.estimateWaitSeconds(5, slots, NOW)).isEqualTo(120 + 300);
        assertThat(queue.estimateWaitSeconds(7, slots, NOW)).isEqualTo(60 + 600);
    }

    @Test
    void aFullHouseOfPassesIssuedJustNowMeansTheWholeLifetime() {
        QueueService queue = service(500, 300, TTL_MS);
        long[] slots = slots(300_000, 300_000, 300_000, 300_000); // 정원이 작아 보이게 줄인 예: 모두 방금 입장했다

        assertThat(service(4, 300, TTL_MS).estimateWaitSeconds(1, slots, NOW)).isEqualTo(300);
        assertThat(service(4, 300, TTL_MS).estimateWaitSeconds(5, slots, NOW)).isEqualTo(600);
        assertThat(queue.estimateWaitSeconds(1, new long[0], NOW)).isEqualTo(300); // 슬롯 정보가 없으면 입장권 수명
    }

    @Test
    void emptySlotsAreUsedBeforeAnyPassHasToEnd() {
        QueueService queue = service(3, 300, TTL_MS);
        long[] slots = slots(0, 100_000, 200_000); // 하나는 비어 있다

        assertThat(queue.estimateWaitSeconds(1, slots, NOW)).isEqualTo(1);
        assertThat(queue.estimateWaitSeconds(2, slots, NOW)).isEqualTo(100);
        assertThat(queue.estimateWaitSeconds(3, slots, NOW)).isEqualTo(200);
        assertThat(queue.estimateWaitSeconds(4, slots, NOW)).isEqualTo(300); // 비어 있던 슬롯을 받은 사람이 5분을 쥔다
    }

    @Test
    void aPassThatAlreadyEndedCountsAsEmpty() {
        QueueService queue = service(2, 300, TTL_MS);
        long[] slots = {NOW - 5_000, NOW + 90_000};

        assertThat(queue.estimateWaitSeconds(1, slots, NOW)).isEqualTo(1);
        assertThat(queue.estimateWaitSeconds(2, slots, NOW)).isEqualTo(90);
    }

    @Test
    void aPersonAlreadyAdmittedWhileBeingCountedIsTreatedAsFirstInLine() {
        QueueService queue = service(3, 300, TTL_MS);
        long[] slots = slots(0, 100_000, 200_000);

        // 순번 0: 줄에 선 직후 순번을 읽기 전에 이미 입장 처리됐다. 예전에는 (0 - 1) % 슬롯 수 = -1로 배열 범위를 벗어나 500이 났다.
        assertThat(queue.estimateWaitSeconds(0, slots, NOW)).isEqualTo(1);
        assertThat(queue.estimateWaitSeconds(0, slots(60_000, 120_000, 300_000), NOW)).isEqualTo(60);
    }

    @Test
    void anIntervalOfAWholeSecondOrMoreIsKeptAsIs() {
        QueueService queue = service(2, 2_500, TTL_MS);

        assertThat(queue.estimateWaitSeconds(1, slots(0, 0), NOW)).isEqualTo(3); // 2.5초는 올림
    }

    @Test
    void theRoundLengthFollowsThePassLifetime() {
        QueueService queue = service(2, 300, 60_000);

        assertThat(queue.estimateWaitSeconds(3, slots(10_000, 20_000), NOW)).isEqualTo(10 + 60);
    }
}
