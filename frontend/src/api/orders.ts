import { apiClient } from "./client";
import type { Order, OrderHistoryItem, PaymentResult } from "../types";

export function createOrder(reservationId: number) {
  return apiClient.post<Order>("/orders", { reservationId }).then((res) => res.data);
}

export function payOrder(orderId: number, paymentId: string) {
  return apiClient.post<PaymentResult>(`/orders/${orderId}/payment`, { paymentId }).then((res) => res.data);
}

export function cancelOrder(orderId: number) {
  return apiClient.delete(`/orders/${orderId}`).then(() => undefined);
}

export function fetchOrders(params: { page?: number; size?: number } = {}) {
  return apiClient
    .get<{ content: OrderHistoryItem[] }>("/orders", { params })
    .then((res) => res.data.content);
}
