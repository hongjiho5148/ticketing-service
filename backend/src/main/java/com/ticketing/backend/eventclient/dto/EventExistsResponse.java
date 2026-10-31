package com.ticketing.backend.eventclient.dto;

/** salesClosed: booking for this show is over (seats can be taken until 2 hours before it starts). */
public record EventExistsResponse(Long id, String title, boolean salesClosed) {
}
