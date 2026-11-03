package com.ticketing.eventservice.event.dto;

/** 공연 옆에 보여 주는 후기 요약이다. 아직 후기를 쓴 사람이 없으면 averageRating은 null이다. */
public record RatingStats(Double averageRating, long reviewCount) {

    public static final RatingStats NONE = new RatingStats(null, 0);
}
