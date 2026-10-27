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
     * Marks a whole batch of admitted entries PASSED in one statement (the unique index on queue_token finds them), so a
     * sweep costs one UPDATE per event instead of a SELECT and an UPDATE per person. Transactional here, on the
     * repository proxy, so it does not depend on the caller having a transaction.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WaitingQueue w set w.status = com.ticketing.backend.queue.QueueEntryStatus.PASSED, w.passedAt = :now"
            + " where w.queueToken in :tokens and w.status = com.ticketing.backend.queue.QueueEntryStatus.WAITING")
    int markPassed(@Param("tokens") Collection<String> tokens, @Param("now") LocalDateTime now);
}
