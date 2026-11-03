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
     * 아직 PENDING인 양도를 한 문장으로 닫는다. 동시에 들어온 여러 응답(수락, 거절, 취소) 중 첫 번째만
     * WHERE 절에 맞고, 나머지는 0행이라 거절된다.
     */
    @Modifying
    @Transactional
    @Query("update TicketTransfer t set t.status = :to, t.pendingOrderId = null, t.respondedAt = :now "
            + "where t.id = :id and t.status = com.ticketing.orderservice.transfer.TransferStatus.PENDING")
    int closeIfPending(@Param("id") Long id, @Param("to") TransferStatus to, @Param("now") LocalDateTime now);
}
