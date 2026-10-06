import { apiClient } from "./client";
import type { PointSummary } from "../types";

export function fetchPoints() {
  return apiClient.get<PointSummary>("/orders/points").then((res) => res.data);
}
