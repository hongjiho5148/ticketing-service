import { apiClient } from "./client";
import type { ScanResult, Ticket } from "../types";

export function fetchMyTicket(orderId: number) {
  return apiClient.get<Ticket>(`/orders/${orderId}/ticket`).then((res) => res.data);
}

export function scanTicket(token: string) {
  return apiClient.post<ScanResult>("/admin/tickets/scan", { token }).then((res) => res.data);
}
