import type { AvailableCoupon } from "../types";

/** "10% 할인" / "5,000원 할인" */
export function couponBenefitLabel(coupon: Pick<AvailableCoupon, "discountType" | "discountValue">): string {
  return coupon.discountType === "PERCENT"
    ? `${coupon.discountValue}% 할인`
    : `${coupon.discountValue.toLocaleString()}원 할인`;
}

/** "2027. 12. 31.까지" */
export function couponExpiryLabel(validTo: string): string {
  return `${new Date(validTo).toLocaleDateString("ko-KR")}까지`;
}
