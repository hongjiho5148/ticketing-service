import { apiClient } from "./client";
import type { QueueEnterResult, QueueStatusResult } from "../types";

export function enterQueue(eventId: number) {
  return apiClient.post<QueueEnterResult>("/queue/enter", { eventId }).then((res) => res.data);
}

export function fetchQueueStatus(queueToken: string) {
  return apiClient.get<QueueStatusResult>("/queue/status", { params: { queueToken } }).then((res) => res.data);
}
