package com.ticketing.eventservice.wishlist.dto;

import jakarta.validation.constraints.NotNull;

public record WishlistAddRequest(@NotNull Long eventId) {
}
