package com.ticketing.eventservice.wishlist;

import com.ticketing.eventservice.auth.SecurityUtil;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import com.ticketing.eventservice.wishlist.dto.WishlistAddRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @GetMapping
    public List<EventSummaryResponse> list() {
        return wishlistService.list(SecurityUtil.getCurrentUserId());
    }

    @PostMapping
    public ResponseEntity<Void> add(@Valid @RequestBody WishlistAddRequest request) {
        wishlistService.add(SecurityUtil.getCurrentUserId(), request.eventId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> remove(@PathVariable Long eventId) {
        wishlistService.remove(SecurityUtil.getCurrentUserId(), eventId);
        return ResponseEntity.noContent().build();
    }
}
