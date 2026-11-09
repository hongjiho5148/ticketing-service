package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 내 앞에 몇 명이 있는지로 추정하는 대기시간이다. Spring도 Redis도 없이 계산식만 확인한다. */
class QueueWaitEstimateTest {

    private static QueueService service(int capacity, long intervalMs, long passTtlMs) {
        return new QueueService(null, null, null, capacity, intervalMs, passTtlMs, true, 300_000, 120_000, new SimpleMeterRegistry());
    }

    @Test
    void whoeverFitsInTheFreeSlotsGetsInOnTheNextTick() {
        QueueService queue = service(500, 300, 300_000);

        assertThat(queue.estimateWaitSeconds(1, 500)).isEqualTo(1); // 주기 0.3초는 올림해서 1초
        assertThat(queue.estimateWaitSeconds(500, 500)).isEqualTo(1);
        assertThat(queue.estimateWaitSeconds(8, 8)).isEqualTo(1);
    }

    @Test
    void withNoFreeSlotEveryRoundOfCapacityCostsOnePassLifetime() {
        QueueService queue = service(500, 300, 300_000);

        assertThat(queue.estimateWaitSeconds(1, 0)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(500, 0)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(501, 0)).isEqualTo(600);
        assertThat(queue.estimateWaitSeconds(2_500, 0)).isEqualTo(1_500);
    }

    @Test
    void thePeopleBeyondTheFreeSlotsAreWhatCounts() {
        QueueService queue = service(500, 300, 300_000);

        assertThat(queue.estimateWaitSeconds(9, 8)).isEqualTo(300); // 한 명이 슬롯을 넘친다
        assertThat(queue.estimateWaitSeconds(10, 8)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(508, 8)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(509, 8)).isEqualTo(600);
    }

    @Test
    void aSmallCapacityRoundsUp() {
        QueueService queue = service(3, 300, 300_000);

        assertThat(queue.estimateWaitSeconds(1, 0)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(3, 0)).isEqualTo(300);
        assertThat(queue.estimateWaitSeconds(7, 0)).isEqualTo(900); // 3바퀴
    }

    @Test
    void theEstimateFollowsThePassLifetime() {
        QueueService queue = service(100, 300, 60_000);

        assertThat(queue.estimateWaitSeconds(101, 0)).isEqualTo(120);
    }

    @Test
    void anIntervalOfAWholeSecondOrMoreIsKeptAsIs() {
        QueueService queue = service(100, 2_500, 300_000);

        assertThat(queue.estimateWaitSeconds(1, 100)).isEqualTo(3); // 2.5초는 올림
    }
}
