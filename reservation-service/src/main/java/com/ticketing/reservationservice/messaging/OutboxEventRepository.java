package com.ticketing.reservationservice.messaging;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    long countBySentAtIsNull();

    /** 아직 나가지 못하고 기다리는 가장 오래된 이벤트다(밀린 이벤트가 얼마나 오래 막혀 있는지 보기 위해). */
    java.util.Optional<OutboxEvent> findFirstBySentAtIsNullOrderByIdAsc();

    /**
     * 아무도 유효한 임대를 쥐고 있지 않은 가장 오래된 전송 전 행을 최대 100개 잠근다. 다른 트랜잭션이 이미 잠근 행은
     * 기다리지 않고 건너뛴다. 그 행들을 {@link #assign}하는 트랜잭션 안에서 실행해야 한다(OutboxClaimer 참고).
     * {@code topicPattern}은 SQL LIKE 패턴이다("%" = 모든 토픽).
     */
    @Query(nativeQuery = true, value = "select id from outbox_event where sent_at is null and topic like :topicPattern "
            + "and (claimed_until is null or claimed_until < :now) order by id limit 100 for update skip locked")
    List<Long> lockUnclaimedIds(@Param("now") LocalDateTime now, @Param("topicPattern") String topicPattern);

    @Modifying
    @Query("update OutboxEvent e set e.claimedBy = :token, e.claimedUntil = :until where e.id in :ids")
    int assign(@Param("token") String token, @Param("until") LocalDateTime until, @Param("ids") List<Long> ids);

    /** 이 호출자가 방금 가져가서 아직 보내지 않은 행이다. 기록된 순서대로 나가도록 오래된 것부터 정렬한다. */
    List<OutboxEvent> findByClaimedByAndSentAtIsNullOrderByIdAsc(String claimedBy);

    /** 전송에 실패해서 남은 행을 돌려주므로, 다음 폴링이 임대가 끝나기를 기다리지 않고 바로 재시도할 수 있다. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.claimedBy = null, e.claimedUntil = null where e.claimedBy = :token and e.sentAt is null")
    int release(@Param("token") String token);

    /** 아직 전송 전일 때만 표시하므로, 한 행이 두 번 전송 완료로 기록될 수 없다. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.sentAt = :now where e.id = :id and e.sentAt is null")
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from OutboxEvent e where e.sentAt is not null and e.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
