import { apiClient } from "./client";
import type { EventDetail, EventListResponse, EventStatus, Seat } from "../types";

export function fetchEvents(params: { status?: EventStatus; page?: number; size?: number } = {}) {
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
