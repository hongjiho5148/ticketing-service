import { apiClient } from "./client";
import type { Order, OrderHistoryItem, PaymentResult } from "../types";

export function createOrder(reservationId: number) {
  return apiClient.post<Order>("/orders", { reservationId }).then((res) => res.data);
}

export function payOrder(orderId: number, method = "MOCK") {
  return apiClient.post<PaymentResult>(`/orders/${orderId}/payment`, { method }).then((res) => res.data);
}

export function fetchOrders(params: { page?: number; size?: number } = {}) {
  return apiClient
    .get<{ content: OrderHistoryItem[] }>("/orders", { params })
    .then((res) => res.data.content);
}
