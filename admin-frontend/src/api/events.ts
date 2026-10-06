import { apiClient } from "./client";
import type { EventDetail } from "../types";

export function fetchEventDetail(eventId: number) {
  return apiClient.get<EventDetail>(`/events/${eventId}`).then((res) => res.data);
}
