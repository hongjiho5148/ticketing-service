package com.ticketing.backend.eventclient.dto;

/** salesClosed: 이 공연의 예매가 끝났다(좌석은 공연 시작 2시간 전까지 잡을 수 있다). */
public record EventExistsResponse(Long id, String title, boolean salesClosed) {
}
