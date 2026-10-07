package com.ticketing.eventservice.alert;

/** Published when seats of an event go back on sale - what the cancellation-ticket alert listens for. */
public record SeatsReleasedEvent(Long eventId, int seatCount) {
}
