package com.ticketing.orderservice.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByPaymentId(String paymentId);

    List<PaymentAttempt> findByOrderIdOrderByIdDesc(Long orderId);
}
