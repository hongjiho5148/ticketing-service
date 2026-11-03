package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.internal.dto.HoldSeatRequest;
import com.ticketing.eventservice.internal.dto.ReleaseSeatRequest;
import com.ticketing.eventservice.internal.dto.SellSeatRequest;
import com.ticketing.eventservice.internal.dto.SeatBatchRequest;
import com.ticketing.eventservice.internal.dto.SeatBatchResponse;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 사이 호출 전용이다. 공개 게이트웨이를 거치지 않고 docker 네트워크 안에서 직접 닿는다. */
@RestController
@RequestMapping("/internal/seats")
public class SeatInternalController {

    private final SeatInternalService seatInternalService;

    public SeatInternalController(SeatInternalService seatInternalService) {
        this.seatInternalService = seatInternalService;
    }

    @GetMapping("/{seatId}")
    public SeatDetailResponse getSeat(@PathVariable Long seatId) {
        return seatInternalService.getSeat(seatId);
    }

    @PostMapping("/batch")
    public SeatBatchResponse getSeats(@Valid @RequestBody SeatBatchRequest request) {
        return new SeatBatchResponse(seatInternalService.getSeats(request.seatIds()));
    }

    @PostMapping("/{seatId}/hold")
    public SeatDetailResponse hold(@PathVariable Long seatId, @Valid @RequestBody HoldSeatRequest request) {
        return seatInternalService.hold(seatId, request.holdExpireAt());
    }

    /** 본문은 선택이다. 없으면 좌석을 조건 없이 푼다(옛 호출자를 위해 남겨 두었다). */
    @PostMapping("/{seatId}/release")
    public ResponseEntity<Void> release(
            @PathVariable Long seatId, @RequestBody(required = false) ReleaseSeatRequest request) {
        seatInternalService.release(seatId, request == null ? null : request.holdExpireAt());
        return ResponseEntity.ok().build();
    }

    /** 본문은 선택이다. 없으면 좌석을 조건 없이 판매 처리한다(옛 호출자를 위해 남겨 두었다). */
    @PostMapping("/{seatId}/sell")
    public ResponseEntity<Void> sell(
            @PathVariable Long seatId, @RequestBody(required = false) SellSeatRequest request) {
        seatInternalService.sell(seatId, request == null ? null : request.holdExpireAt());
        return ResponseEntity.ok().build();
    }
}
