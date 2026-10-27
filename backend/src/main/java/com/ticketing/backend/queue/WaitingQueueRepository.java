package com.ticketing.backend.queue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueue, Long> {

    Optional<WaitingQueue> findByQueueToken(String queueToken);

    List<WaitingQueue> findByUserIdAndEventId(Long userId, Long eventId);
}
