package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a batch of unsent rows for one relay poll: lock them, mark them with the poll's token and lease, commit - a short
 * database-only transaction, finished before any network call is made.
 *
 * <p>Two things keep concurrent relays from blocking or deadlocking each other (a single {@code UPDATE ... ORDER BY ...
 * LIMIT} did deadlock against the "mark sent" updates in a test): rows another relay has locked are skipped rather than
 * waited for (SKIP LOCKED), and READ COMMITTED means the scan takes row locks only, not gap locks that would stall new
 * events being recorded.
 */
@Component
public class OutboxClaimer {

    private final OutboxEventRepository repository;
    private final TransactionTemplate transaction;

    public OutboxClaimer(OutboxEventRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Returns how many rows this poll now holds a lease on. */
    public int claim(String token, LocalDateTime now, LocalDateTime until, String topicPattern) {
        Integer claimed = transaction.execute(status -> {
            List<Long> ids = repository.lockUnclaimedIds(now, topicPattern);
            if (ids.isEmpty()) {
                return 0;
            }
            repository.assign(token, until, ids);
            return ids.size();
        });
        return claimed == null ? 0 : claimed;
    }
}
