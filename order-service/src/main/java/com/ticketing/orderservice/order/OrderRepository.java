package com.ticketing.orderservice.order;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    /** Rows of [eventId, status, orderCount, totalPriceSum, refundedSum] for the admin sales summary. */
    @Query("select o.eventId, o.status, count(o), sum(o.totalPrice), coalesce(sum(p.refundedAmount), 0) "
            + "from Orders o left join Payment p on p.order = o group by o.eventId, o.status")
    List<Object[]> aggregateByEventAndStatus();

    Page<Orders> findByUserId(Long userId, Pageable pageable);

    List<Orders> findByStatusAndEventStartAtBetween(OrderStatus status, LocalDateTime from, LocalDateTime to);

    List<Orders> findByStatusAndReminderSentAtIsNullAndEventStartAtBetween(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    boolean existsByUserIdAndEventIdAndStatus(Long userId, Long eventId, OrderStatus status);
}
