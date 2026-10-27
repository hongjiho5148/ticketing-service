package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** The relay's batch: oldest unsent first, so events leave in the order they were recorded. */
    List<OutboxEvent> findTop100BySentAtIsNullOrderByIdAsc();

    long countBySentAtIsNull();

    /** Only marks it if it is still unsent, so two relays racing on the same row can't both claim it. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.sentAt = :now where e.id = :id and e.sentAt is null")
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from OutboxEvent e where e.sentAt is not null and e.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
