package com.ticketing.orderservice.order;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    Page<Orders> findByUserId(Long userId, Pageable pageable);

    List<Orders> findByStatusAndEventStartAtBetween(OrderStatus status, LocalDateTime from, LocalDateTime to);

    List<Orders> findByStatusAndReminderSentAtIsNullAndEventStartAtBetween(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    boolean existsByUserIdAndEventIdAndStatus(Long userId, Long eventId, OrderStatus status);
}
