import { apiClient } from "./client";
import type { ReviewListResponse } from "../types";

export function fetchReviews(eventId: number, params: { page?: number; size?: number } = {}) {
  return apiClient.get<ReviewListResponse>(`/events/${eventId}/reviews`, { params }).then((res) => res.data);
}

export function createReview(eventId: number, rating: number, content: string) {
  return apiClient.post(`/events/${eventId}/reviews`, { rating, content }).then(() => undefined);
}
