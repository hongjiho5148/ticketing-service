package com.ticketing.eventservice.internal.dto;

import java.time.LocalDateTime;

/**
 * 호출한 쪽이 풀려는 홀드다. 좌석을 잡을 때 받은 holdExpireAt이다. 홀드를 지정한 해제는 좌석이 정확히 그 홀드에
 * 아직 잡혀 있는 동안에만 효과가 있다(Seat.isHeldWith 참고).
 */
public record ReleaseSeatRequest(LocalDateTime holdExpireAt) {
}
