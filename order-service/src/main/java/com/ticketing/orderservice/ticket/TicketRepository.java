package com.ticketing.orderservice.ticket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    boolean existsByOrderId(Long orderId);

    Optional<Ticket> findByOrderId(Long orderId);

    Optional<Ticket> findByTokenJti(String tokenJti);

    /** Tickets the user can use right now - bought by them and never transferred, or transferred to them. */
    @Query("select t from Ticket t where coalesce(t.order.ownerId, t.order.userId) = :userId order by t.issuedAt desc")
    List<Ticket> findByHolderOrderByIssuedAtDesc(@Param("userId") Long userId);

    /**
     * Atomic ISSUED-&gt;USED transition at the DB level: only one of two concurrent scans of the
     * same ticket can ever match this WHERE clause, so the other gets 0 updated rows instead of
     * both racing past an app-level read-then-write check.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = com.ticketing.orderservice.ticket.TicketStatus.USED, t.usedAt = :usedAt "
            + "WHERE t.tokenJti = :tokenJti AND t.status = com.ticketing.orderservice.ticket.TicketStatus.ISSUED")
    int markUsedIfIssued(@Param("tokenJti") String tokenJti, @Param("usedAt") LocalDateTime usedAt);
}
