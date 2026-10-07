package com.ticketing.eventservice.event.dto;

/** Review summary shown next to an event; averageRating is null while nobody has reviewed it yet. */
public record RatingStats(Double averageRating, long reviewCount) {

    public static final RatingStats NONE = new RatingStats(null, 0);
}
