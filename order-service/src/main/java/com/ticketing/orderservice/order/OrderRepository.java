package com.ticketing.orderservice.order;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    /** Rows of [eventId, status, orderCount, totalPriceSum, refundedSum] for the admin sales summary. */
    @Query("select o.eventId, o.status, count(o), sum(o.totalPrice), coalesce(sum(p.refundedAmount), 0) "
            + "from Orders o left join Payment p on p.order = o group by o.eventId, o.status")
    List<Object[]> aggregateByEventAndStatus();

    Page<Orders> findByUserId(Long userId, Pageable pageable);

    /** Admin list: every order, or only those in one status (null = no filter). */
    @Query("select o from Orders o where (:status is null or o.status = :status)")
    Page<Orders> findAllByOptionalStatus(@Param("status") OrderStatus status, Pageable pageable);

    List<Orders> findByStatusAndEventStartAtBetween(OrderStatus status, LocalDateTime from, LocalDateTime to);

    List<Orders> findByStatusAndReminderSentAtIsNullAndEventStartAtBetween(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    /** Review eligibility follows the ticket: after a transfer it is the recipient who attended, not the buyer. */
    @Query("select count(o) > 0 from Orders o where coalesce(o.ownerId, o.userId) = :userId "
            + "and o.eventId = :eventId and o.status = :status")
    boolean existsByHolderAndEventIdAndStatus(
            @Param("userId") Long userId, @Param("eventId") Long eventId, @Param("status") OrderStatus status);

    /**
     * Row lock for the operations that must not interleave on one order: cancel/refund versus
     * creating or accepting a transfer. Whichever takes the lock second sees the other's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :id")
    Optional<Orders> findByIdForUpdate(@Param("id") Long id);
}
