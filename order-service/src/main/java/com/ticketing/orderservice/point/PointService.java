package com.ticketing.orderservice.point;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// noRollbackFor ApiException: OrderService.pay()가 여기서 던진 실패를 잡아서 같은 트랜잭션 안에서 계속
// 진행한다(카드 결제 환불, 주문 실패 처리). 이 설정이 없으면 이 @Transactional 메서드 중 하나에서 처음
// ApiException이 나가는 순간 공유 트랜잭션 전체가 롤백 전용으로 표시되어 그 복구 경로가 커밋될 수 없다.
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

    /** 아직 원장 행 없이 포인트를 쓴다. 전체 결제가 끝난 뒤에 호출한 쪽이 USE를 기록한다. */
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

    /** 취소된 주문이 적립했던 것을 회수한다. 잔액이 감당하는 만큼만 회수하고 0 아래로 내려가지 않는다. */
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
