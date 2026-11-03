package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.seat.dto.SeatResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** [eventId, grade, status, seatCount, priceSum] 행들이다. 관리자 대시보드가 이를 공연별 통계로 접는다. */
    @Query("select s.event.id, s.seatGrade, s.status, count(s), sum(s.price) from Seat s "
            + "group by s.event.id, s.seatGrade, s.status")
    List<Object[]> aggregateStats();

    /**
     * 좌석 배치도가 그리는 좌석 목록이다. 응답 레코드로 바로 읽으므로 Seat 엔티티를 만들거나 영속성 컨텍스트에서
     * 추적하거나 더티 체크하지 않는다. 요청 하나에 좌석이 수천 개일 때 비용의 대부분이 그것이다. id 순으로
     * 정렬해서 응답이 안정적이다(예전에는 인덱스가 우연히 돌려주는 순서였다).
     */
    @Query("select new com.ticketing.eventservice.seat.dto.SeatResponse(s.id, s.seatNo, s.seatGrade, s.section, "
            + "s.rowNo, s.seatNumber, s.price, s.status) from Seat s where s.event.id = :eventId order by s.id")
    List<SeatResponse> listForEvent(@Param("eventId") Long eventId);

    @Query("select new com.ticketing.eventservice.seat.dto.SeatResponse(s.id, s.seatNo, s.seatGrade, s.section, "
            + "s.rowNo, s.seatNumber, s.price, s.status) from Seat s "
            + "where s.event.id = :eventId and s.seatGrade = :grade order by s.id")
    List<SeatResponse> listForEventAndGrade(@Param("eventId") Long eventId, @Param("grade") String grade);

    /** 한 공연의 [grade, seatCount, availableCount] 행들이다. 상세 화면에 필요한 것이며 데이터베이스가 계산한다. */
    @Query("select s.seatGrade, count(s), sum(case when s.status = com.ticketing.eventservice.seat.SeatStatus.AVAILABLE "
            + "then 1 else 0 end) from Seat s where s.event.id = :eventId group by s.seatGrade")
    List<Object[]> gradeSummary(@Param("eventId") Long eventId);

    /** 한 공연의 [section, grade, price, seatCount, availableCount] 행들이다(구역 하나는 등급과 가격이 하나다). */
    @Query("select s.section, min(s.seatGrade), min(s.price), count(s), "
            + "sum(case when s.status = com.ticketing.eventservice.seat.SeatStatus.AVAILABLE then 1 else 0 end) "
            + "from Seat s where s.event.id = :eventId group by s.section")
    List<Object[]> sectionSummary(@Param("eventId") Long eventId);

    List<Seat> findByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHoldExpireAtBefore(SeatStatus status, LocalDateTime before);
}
