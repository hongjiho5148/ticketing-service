package com.ticketing.orderservice.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

    /** Spends points atomically; 0 rows updated means the balance wasn't enough (checked inside the same statement). */
    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = p.balance - :amount WHERE p.userId = :userId AND p.balance >= :amount")
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = p.balance + :amount WHERE p.userId = :userId")
    int credit(@Param("userId") Long userId, @Param("amount") long amount);
}
