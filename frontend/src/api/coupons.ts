import { apiClient } from "./client";
import type { AvailableCoupon, CouponHistoryItem } from "../types";

export function fetchAvailableCoupons() {
  return apiClient.get<AvailableCoupon[]>("/orders/coupons/available").then((res) => res.data);
}

export function fetchCouponHistory() {
  return apiClient.get<CouponHistoryItem[]>("/orders/coupons").then((res) => res.data);
}
