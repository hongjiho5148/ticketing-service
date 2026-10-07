package com.ticketing.eventservice.review;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByEventIdOrderByCreatedAtDesc(Long eventId, Pageable pageable);

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.eventId = :eventId")
    Double findAverageRatingByEventId(@Param("eventId") Long eventId);

    long countByEventId(Long eventId);

    /** Rows of [eventId, averageRating, reviewCount] - one query for a whole page of events instead of one each. */
    @Query("SELECT r.eventId, AVG(r.rating), COUNT(r) FROM Review r WHERE r.eventId IN :eventIds GROUP BY r.eventId")
    List<Object[]> ratingStats(@Param("eventIds") Collection<Long> eventIds);
}
