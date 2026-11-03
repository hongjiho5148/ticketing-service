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

    /** 사용자가 지금 쓸 수 있는 티켓이다. 본인이 샀고 양도된 적이 없거나, 본인에게 양도된 티켓이다. */
    @Query("select t from Ticket t where coalesce(t.order.ownerId, t.order.userId) = :userId order by t.issuedAt desc")
    List<Ticket> findByHolderOrderByIssuedAtDesc(@Param("userId") Long userId);

    /**
     * DB 수준의 원자적인 ISSUED-&gt;USED 전환이다. 같은 티켓에 동시에 들어온 두 번의 스캔 중 이 WHERE 절에
     * 맞는 것은 하나뿐이고, 다른 쪽은 갱신된 행이 0이다. 둘이 앱 수준의 읽고-쓰기 검사를 함께 통과하는 일이 없다.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = com.ticketing.orderservice.ticket.TicketStatus.USED, t.usedAt = :usedAt "
            + "WHERE t.tokenJti = :tokenJti AND t.status = com.ticketing.orderservice.ticket.TicketStatus.ISSUED")
    int markUsedIfIssued(@Param("tokenJti") String tokenJti, @Param("usedAt") LocalDateTime usedAt);
}
