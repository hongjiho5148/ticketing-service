package com.ticketing.orderservice.verification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, Long> {

    boolean existsByOrderId(Long orderId);
}
