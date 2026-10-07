package com.ticketing.orderservice.transfer;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TicketTransferRepository extends JpaRepository<TicketTransfer, Long> {

    boolean existsByPendingOrderId(Long orderId);

    @Query("select t.pendingOrderId from TicketTransfer t where t.pendingOrderId in :orderIds")
    List<Long> pendingOrderIdsIn(@Param("orderIds") List<Long> orderIds);

    default Set<Long> findPendingOrderIds(List<Long> orderIds) {
        return new HashSet<>(pendingOrderIdsIn(orderIds));
    }

    @Query("select t from TicketTransfer t where t.fromUserId = :userId or t.toUserId = :userId order by t.id desc")
    List<TicketTransfer> findInvolving(@Param("userId") Long userId, Pageable pageable);

    /**
     * Closes a still-PENDING transfer in one statement. Of several concurrent answers (accept,
     * decline, cancel) only the first matches the WHERE clause; the rest get 0 rows and are refused.
     */
    @Modifying
    @Transactional
    @Query("update TicketTransfer t set t.status = :to, t.pendingOrderId = null, t.respondedAt = :now "
            + "where t.id = :id and t.status = com.ticketing.orderservice.transfer.TransferStatus.PENDING")
    int closeIfPending(@Param("id") Long id, @Param("to") TransferStatus to, @Param("now") LocalDateTime now);
}
