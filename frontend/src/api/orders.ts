import { apiClient } from "./client";
import type { Checkout, Order, OrderHistoryListResponse, PaymentResult, RefundPreview } from "../types";

export function createOrder(reservationId: number) {
  return apiClient.post<Order>("/orders", { reservationId }).then((res) => res.data);
}

export interface PaymentIntent {
  paymentId: string;
  amount: number;
}

/** 결제창이 써야 하는 결제 id(와 금액)를 서버에 묻는다. */
export function createPaymentIntent(orderId: number) {
  return apiClient.post<PaymentIntent>(`/orders/${orderId}/payment-intent`).then((res) => res.data);
}

export function payOrder(orderId: number, paymentId: string) {
  return apiClient.post<PaymentResult>(`/orders/${orderId}/payment`, { paymentId }).then((res) => res.data);
}

export function cancelOrder(orderId: number, expectedRefund?: number) {
  return apiClient
    .delete(`/orders/${orderId}`, { params: expectedRefund === undefined ? {} : { expectedRefund } })
    .then(() => undefined);
}

// 결제를 마무리할 결제 대기 주문이다. 좌석 홀드가 끝났다면 거절된다(409).
export function fetchCheckout(orderId: number) {
  return apiClient.get<Checkout>(`/orders/${orderId}/checkout`).then((res) => res.data);
}

export function fetchOrders(params: { page?: number; size?: number } = {}) {
  return apiClient.get<OrderHistoryListResponse>("/orders", { params }).then((res) => res.data);
}

// 구매자의 확인이다. 결제 전에 서버에 기록하며, 서버는 확인되지 않은 주문의 확정을 거절한다.
export function verifyIdentity(orderId: number) {
  return apiClient.post(`/orders/${orderId}/verify-identity`, { agreed: true }).then(() => undefined);
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
