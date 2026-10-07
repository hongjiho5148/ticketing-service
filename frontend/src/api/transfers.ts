import { apiClient } from "./client";
import type { TransferItem } from "../types";

export function createTransfer(orderId: number, email: string) {
  return apiClient.post<TransferItem>(`/orders/${orderId}/transfer`, { email }).then((res) => res.data);
}

export function fetchTransfers() {
  return apiClient.get<TransferItem[]>("/orders/transfers").then((res) => res.data);
}

export function acceptTransfer(transferId: number) {
  return apiClient.post(`/orders/transfers/${transferId}/accept`).then(() => undefined);
}

export function declineTransfer(transferId: number) {
  return apiClient.post(`/orders/transfers/${transferId}/decline`).then(() => undefined);
}

export function cancelTransfer(transferId: number) {
  return apiClient.post(`/orders/transfers/${transferId}/cancel`).then(() => undefined);
}
