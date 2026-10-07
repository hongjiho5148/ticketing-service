import { apiClient } from "./client";
import type { AlertStatus } from "../types";

// "오픈 알림" (UPCOMING events) and "취소표 알림" (sold-out events) share one shape, so both
// kinds are addressed by their path segment.
export type AlertKind = "open-alert" | "waitlist";

export function fetchAlertStatus(eventId: number, kind: AlertKind) {
  return apiClient.get<AlertStatus>(`/events/${eventId}/${kind}`).then((res) => res.data);
}

export function subscribeAlert(eventId: number, kind: AlertKind) {
  return apiClient.post<AlertStatus>(`/events/${eventId}/${kind}`).then((res) => res.data);
}

export function unsubscribeAlert(eventId: number, kind: AlertKind) {
  return apiClient.delete(`/events/${eventId}/${kind}`).then(() => undefined);
}
