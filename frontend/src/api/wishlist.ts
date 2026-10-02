import { apiClient } from "./client";
import type { EventSummary } from "../types";

export function fetchWishlist() {
  return apiClient.get<EventSummary[]>("/wishlist").then((res) => res.data);
}

export function addToWishlist(eventId: number) {
  return apiClient.post("/wishlist", { eventId }).then(() => undefined);
}

export function removeFromWishlist(eventId: number) {
  return apiClient.delete(`/wishlist/${eventId}`).then(() => undefined);
}
