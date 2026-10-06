import { apiClient } from "./client";
import type { Order, OrderHistoryListResponse, PaymentResult, RefundPreview } from "../types";

export function createOrder(reservationId: number) {
  return apiClient.post<Order>("/orders", { reservationId }).then((res) => res.data);
}

export function payOrder(orderId: number, paymentId: string) {
  return apiClient.post<PaymentResult>(`/orders/${orderId}/payment`, { paymentId }).then((res) => res.data);
}

export function cancelOrder(orderId: number, expectedRefund?: number) {
  return apiClient
    .delete(`/orders/${orderId}`, { params: expectedRefund === undefined ? {} : { expectedRefund } })
    .then(() => undefined);
}

export function fetchOrders(params: { page?: number; size?: number } = {}) {
  return apiClient.get<OrderHistoryListResponse>("/orders", { params }).then((res) => res.data);
}

export function applyCoupon(orderId: number, code: string) {
  return apiClient.post<Order>(`/orders/${orderId}/apply-coupon`, { code }).then((res) => res.data);
}

export function removeCoupon(orderId: number) {
  return apiClient.delete<Order>(`/orders/${orderId}/coupon`).then((res) => res.data);
}

export function applyPoints(orderId: number, points: number) {
  return apiClient.post<Order>(`/orders/${orderId}/apply-points`, { points }).then((res) => res.data);
}

export function fetchRefundPreview(orderId: number) {
  return apiClient.get<RefundPreview>(`/orders/${orderId}/refund-preview`).then((res) => res.data);
}
