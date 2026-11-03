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

    /** 관리자 매출 요약용 [eventId, status, orderCount, totalPriceSum, refundedSum] 행들이다. */
    @Query("select o.eventId, o.status, count(o), sum(o.totalPrice), coalesce(sum(p.refundedAmount), 0) "
            + "from Orders o left join Payment p on p.order = o group by o.eventId, o.status")
    List<Object[]> aggregateByEventAndStatus();

    Page<Orders> findByUserId(Long userId, Pageable pageable);

    /** {@code since} 이후에 만들어진 아직 결제되지 않은 주문이다. 결제 조정 작업이 계속 지켜보는 범위다. */
    List<Orders> findByStatusAndCreatedAtAfter(OrderStatus status, LocalDateTime since);

    /** 관리자 목록: 모든 주문, 또는 한 상태의 주문만(null이면 필터 없음). */
    @Query("select o from Orders o where (:status is null or o.status = :status)")
    Page<Orders> findAllByOptionalStatus(@Param("status") OrderStatus status, Pageable pageable);

    List<Orders> findByStatusAndEventStartAtBetween(OrderStatus status, LocalDateTime from, LocalDateTime to);

    List<Orders> findByStatusAndReminderSentAtIsNullAndEventStartAtBetween(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    /** 후기를 쓸 수 있는지는 티켓을 따른다. 양도된 뒤에는 구매자가 아니라 받은 사람이 관람한 것이다. */
    @Query("select count(o) > 0 from Orders o where coalesce(o.ownerId, o.userId) = :userId "
            + "and o.eventId = :eventId and o.status = :status")
    boolean existsByHolderAndEventIdAndStatus(
            @Param("userId") Long userId, @Param("eventId") Long eventId, @Param("status") OrderStatus status);

    /**
     * 한 주문에서 서로 끼어들면 안 되는 작업(취소·환불과 양도 생성·수락)을 위한 행 잠금이다.
     * 잠금을 두 번째로 얻은 쪽이 먼저 한 쪽의 결과를 보게 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :id")
    Optional<Orders> findByIdForUpdate(@Param("id") Long id);
}
