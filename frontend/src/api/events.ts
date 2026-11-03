import { apiClient } from "./client";
import type { EventCategory, EventDetail, EventListResponse, EventStatus, Seat } from "../types";

export function fetchEvents(
  params: {
    status?: EventStatus;
    category?: EventCategory;
    // ISO 날짜(YYYY-MM-DD)이고 양쪽 끝을 모두 포함한다. 달력의 한 달 구간에 쓴다.
    startDate?: string;
    endDate?: string;
    keyword?: string;
    sortDir?: "asc" | "desc";
    page?: number;
    size?: number;
  } = {},
) {
  return apiClient.get<EventListResponse>("/events", { params }).then((res) => res.data);
}

export function fetchEventDetail(eventId: number) {
  return apiClient.get<EventDetail>(`/events/${eventId}`).then((res) => res.data);
}

export function fetchEventSeats(eventId: number, grade?: string) {
  return apiClient
    .get<Seat[]>(`/events/${eventId}/seats`, { params: grade ? { grade } : {} })
    .then((res) => res.data);
}
