import { apiClient } from "./client";
import type {
  Coupon,
  CouponCreatePayload,
  EventStats,
  EventSummary,
  EventUpsertPayload,
  OrderSummary,
  ScanResult,
  SeatBlockPayload,
} from "../types";

export function createEvent(payload: EventUpsertPayload) {
  return apiClient.post<EventSummary>("/events/admin", payload).then((res) => res.data);
}

export function updateEvent(eventId: number, payload: EventUpsertPayload) {
  return apiClient.put<EventSummary>(`/events/admin/${eventId}`, payload).then((res) => res.data);
}

export function createSeats(eventId: number, blocks: SeatBlockPayload[]) {
  return apiClient
    .post<{ createdCount: number }>(`/events/admin/${eventId}/seats`, { blocks })
    .then((res) => res.data);
}

export function fetchEventStats() {
  return apiClient.get<EventStats[]>("/events/admin/stats").then((res) => res.data);
}

export function fetchOrderSummary() {
  return apiClient.get<OrderSummary[]>("/admin/orders/summary").then((res) => res.data);
}

export function scanTicket(token: string) {
  return apiClient.post<ScanResult>("/admin/tickets/scan", { token }).then((res) => res.data);
}

export function fetchCoupons() {
  return apiClient.get<Coupon[]>("/admin/coupons").then((res) => res.data);
}

export function createCoupon(payload: CouponCreatePayload) {
  return apiClient.post<Coupon>("/admin/coupons", payload).then((res) => res.data);
}
