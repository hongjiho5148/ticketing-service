package com.ticketing.orderservice.point;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    List<PointTransaction> findTop30ByUserIdOrderByIdDesc(Long userId);

    List<PointTransaction> findByOrderIdAndType(Long orderId, PointTransactionType type);
}
