package com.ticketing.orderservice.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

    /** 포인트를 원자적으로 쓴다. 갱신된 행이 0이면 잔액이 부족했다는 뜻이다(같은 문장 안에서 확인한다). */
    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = p.balance - :amount WHERE p.userId = :userId AND p.balance >= :amount")
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = p.balance + :amount WHERE p.userId = :userId")
    int credit(@Param("userId") Long userId, @Param("amount") long amount);
}
