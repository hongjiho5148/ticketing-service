import { apiClient } from "./client";
import type { ScanResult, Ticket, TicketHistoryItem } from "../types";

export function fetchMyTickets() {
  return apiClient.get<TicketHistoryItem[]>("/orders/tickets").then((res) => res.data);
}

export function fetchMyTicket(orderId: number) {
  return apiClient.get<Ticket>(`/orders/${orderId}/ticket`).then((res) => res.data);
}

export function scanTicket(token: string) {
  return apiClient.post<ScanResult>("/admin/tickets/scan", { token }).then((res) => res.data);
}
