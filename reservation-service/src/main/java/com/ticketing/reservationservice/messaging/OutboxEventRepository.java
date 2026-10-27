package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    long countBySentAtIsNull();

    /** The oldest event still waiting to go out (for how long the backlog has been stuck). */
    java.util.Optional<OutboxEvent> findFirstBySentAtIsNullOrderByIdAsc();

    /**
     * Locks up to 100 of the oldest unsent rows nobody holds a live lease on, skipping rows another transaction has
     * already locked instead of waiting for them. Must run inside the transaction that then {@link #assign}s them (see
     * OutboxClaimer). {@code topicPattern} is a SQL LIKE pattern ("%" = every topic).
     */
    @Query(nativeQuery = true, value = "select id from outbox_event where sent_at is null and topic like :topicPattern "
            + "and (claimed_until is null or claimed_until < :now) order by id limit 100 for update skip locked")
    List<Long> lockUnclaimedIds(@Param("now") LocalDateTime now, @Param("topicPattern") String topicPattern);

    @Modifying
    @Query("update OutboxEvent e set e.claimedBy = :token, e.claimedUntil = :until where e.id in :ids")
    int assign(@Param("token") String token, @Param("until") LocalDateTime until, @Param("ids") List<Long> ids);

    /** What this caller just claimed (and has not sent yet), oldest first so events leave in recorded order. */
    List<OutboxEvent> findByClaimedByAndSentAtIsNullOrderByIdAsc(String claimedBy);

    /** Gives back what a failed send left unsent, so the next poll can retry at once instead of waiting out the lease. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.claimedBy = null, e.claimedUntil = null where e.claimedBy = :token and e.sentAt is null")
    int release(@Param("token") String token);

    /** Only marks it if it is still unsent, so a row can't be recorded as sent twice. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.sentAt = :now where e.id = :id and e.sentAt is null")
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from OutboxEvent e where e.sentAt is not null and e.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
