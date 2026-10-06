import { apiClient } from "./client";
import type { CouponHistoryItem } from "../types";

export function fetchCouponHistory() {
  return apiClient.get<CouponHistoryItem[]>("/orders/coupons").then((res) => res.data);
}
