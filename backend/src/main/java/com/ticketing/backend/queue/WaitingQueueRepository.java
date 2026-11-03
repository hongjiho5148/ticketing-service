package com.ticketing.backend.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueue, Long> {

    Optional<WaitingQueue> findByQueueToken(String queueToken);

    List<WaitingQueue> findByUserIdAndEventId(Long userId, Long eventId);

    /**
     * 입장한 항목 묶음 전체를 한 문장으로 PASSED로 표시한다(queue_token의 유니크 인덱스가 찾는다). 그래서 스윕 한 번에 사람마다
     * SELECT와 UPDATE를 하나씩 하는 대신 공연당 UPDATE 하나가 든다. 호출한 쪽이 트랜잭션을 가지고 있는지에 기대지 않도록
     * 여기 리포지토리 프록시에 Transactional을 붙였다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WaitingQueue w set w.status = com.ticketing.backend.queue.QueueEntryStatus.PASSED, w.passedAt = :now"
            + " where w.queueToken in :tokens and w.status = com.ticketing.backend.queue.QueueEntryStatus.WAITING")
    int markPassed(@Param("tokens") Collection<String> tokens, @Param("now") LocalDateTime now);

    /** 입장하지 못하고 줄을 떠난 사람들이다. markPassed처럼 묶음에 대해 문장 하나다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WaitingQueue w set w.status = com.ticketing.backend.queue.QueueEntryStatus.ABANDONED"
            + " where w.queueToken in :tokens and w.status = com.ticketing.backend.queue.QueueEntryStatus.WAITING")
    int markAbandoned(@Param("tokens") Collection<String> tokens);
}
