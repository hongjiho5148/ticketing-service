package com.ticketing.orderservice.point;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// noRollbackFor ApiException: OrderService.pay() catches the failures thrown from here and carries on
// (refunding the card payment and marking the order failed) inside the same transaction. Without
// this, the first ApiException to leave one of these @Transactional methods would flag the whole
// shared transaction rollback-only and that recovery path could never commit.
@Service
@Transactional(noRollbackFor = ApiException.class)
public class PointService {

    private final PointAccountRepository accountRepository;
    private final PointTransactionRepository transactionRepository;

    public PointService(PointAccountRepository accountRepository, PointTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public long balance(Long userId) {
        return accountRepository.findById(userId).map(PointAccount::getBalance).orElse(0L);
    }

    @Transactional(readOnly = true)
    public List<PointTransaction> history(Long userId) {
        return transactionRepository.findTop30ByUserIdOrderByIdDesc(userId);
    }

    /** Spends points with no ledger row yet - the caller records USE once the whole payment has gone through. */
    public void take(Long userId, long amount) {
        if (accountRepository.deduct(userId, amount) == 0) {
            throw new ApiException(ErrorCode.POINTS_INSUFFICIENT);
        }
    }

    public void give(Long userId, long amount) {
        if (!accountRepository.existsById(userId)) {
            accountRepository.saveAndFlush(new PointAccount(userId));
        }
        accountRepository.credit(userId, amount);
    }

    public void record(Long userId, long delta, PointTransactionType type, Long orderId) {
        transactionRepository.save(new PointTransaction(userId, delta, type, orderId));
    }

    /** Takes back what a cancelled order earned - as much as the balance still covers, never below zero. */
    public void clawbackEarned(Long userId, Long orderId) {
        long earned = transactionRepository.findByOrderIdAndType(orderId, PointTransactionType.EARN).stream()
                .mapToLong(PointTransaction::getDelta)
                .sum();
        long amount = Math.min(earned, balance(userId));
        if (amount > 0 && accountRepository.deduct(userId, amount) == 1) {
            record(userId, -amount, PointTransactionType.CLAWBACK, orderId);
        }
    }
}
