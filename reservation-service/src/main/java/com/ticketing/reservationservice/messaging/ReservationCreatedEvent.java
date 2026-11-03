package com.ticketing.reservationservice.messaging;

/**
 * 예약이 성공적으로 만들어진 뒤 발행한다. 앞으로 소비자(주문/대기열 서비스)가 필요로 할 페이로드다.
 * createdAt은 LocalDateTime이 아니라 일반 ISO-8601 문자열이라, JSR-310 모듈 없이도 Kafka의 기본
 * JsonSerializer로 이 레코드를 직렬화할 수 있다.
 */
public record ReservationCreatedEvent(
        Long reservationId, Long seatId, Long userId, Long eventId, String createdAt) {}
