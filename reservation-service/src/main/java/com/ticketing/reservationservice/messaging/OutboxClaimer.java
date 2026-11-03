package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 릴레이가 한 번 폴링할 때 쓸 전송 전 행 묶음을 가져간다. 행을 잠그고, 이번 폴링의 토큰과 임대 시간을 표시하고,
 * 커밋한다. DB만 쓰는 짧은 트랜잭션이며 네트워크 호출을 하기 전에 끝난다.
 *
 * <p>동시에 도는 릴레이가 서로를 막거나 교착되지 않게 하는 것이 두 가지 있다({@code UPDATE ... ORDER BY ...
 * LIMIT} 한 문장은 테스트에서 "전송 완료 표시" 업데이트와 실제로 교착됐다). 다른 릴레이가 잠근 행은 기다리지 않고
 * 건너뛰며(SKIP LOCKED), READ COMMITTED이므로 스캔은 행 잠금만 잡고 새 이벤트 기록을 막을 수 있는 갭 잠금은
 * 잡지 않는다.
 */
@Component
public class OutboxClaimer {

    private final OutboxEventRepository repository;
    private final TransactionTemplate transaction;

    public OutboxClaimer(OutboxEventRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 이번 폴링이 지금 임대를 쥐고 있는 행의 수를 돌려준다. */
    public int claim(String token, LocalDateTime now, LocalDateTime until, String topicPattern) {
        Integer claimed = transaction.execute(status -> {
            List<Long> ids = repository.lockUnclaimedIds(now, topicPattern);
            if (ids.isEmpty()) {
                return 0;
            }
            repository.assign(token, until, ids);
            return ids.size();
        });
        return claimed == null ? 0 : claimed;
    }
}
