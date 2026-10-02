package com.ticketing.eventservice.orderclient.dto;

/** Mirrors order-service's internal OrderExistsResponse. */
public record OrderExistsResponse(boolean exists) {
}
