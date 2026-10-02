package com.ticketing.eventservice.wishlist;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final EventRepository eventRepository;

    public WishlistService(WishlistRepository wishlistRepository, EventRepository eventRepository) {
        this.wishlistRepository = wishlistRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public List<EventSummaryResponse> list(Long userId) {
        List<Long> eventIds = wishlistRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Wishlist::getCreatedAt).reversed())
                .map(Wishlist::getEventId)
                .toList();
        Map<Long, Event> eventsById = eventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        return eventIds.stream()
                .map(eventsById::get)
                .filter(event -> event != null)
                .map(EventSummaryResponse::from)
                .toList();
    }

    public void add(Long userId, Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
        }
        if (wishlistRepository.existsByUserIdAndEventId(userId, eventId)) {
            return;
        }
        wishlistRepository.save(new Wishlist(userId, eventId));
    }

    public void remove(Long userId, Long eventId) {
        wishlistRepository.deleteByUserIdAndEventId(userId, eventId);
    }
}
