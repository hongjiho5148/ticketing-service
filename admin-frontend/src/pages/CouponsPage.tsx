import { useEffect, useState, type FormEvent } from "react";
import { createCoupon, fetchCoupons } from "../api/admin";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { Coupon, CouponCreatePayload, DiscountType } from "../types";

const EMPTY_FORM: CouponCreatePayload = {
  code: "",
  discountType: "PERCENT",
  discountValue: 10,
  validFrom: "",
  validTo: "",
  maxUses: 100,
};

function describeDiscount(coupon: Coupon) {
  return coupon.discountType === "PERCENT"
    ? `${coupon.discountValue}% 할인`
    : `${coupon.discountValue.toLocaleString()}원 할인`;
}

export function CouponsPage() {
  useDocumentTitle("쿠폰 관리");
  const { showToast } = useToast();

  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [form, setForm] = useState<CouponCreatePayload>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    fetchCoupons()
      .then(setCoupons)
      .catch((err) => setLoadError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  function setField<K extends keyof CouponCreatePayload>(key: K, value: CouponCreatePayload[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setIsSaving(true);
    try {
      const created = await createCoupon(form);
      setCoupons((prev) => [created, ...prev]);
      setForm(EMPTY_FORM);
      showToast(`쿠폰 ${created.code}이(가) 만들어졌어요.`);
    } catch (err) {
      setFormError(extractErrorMessage(err));
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div>
      <div className="admin-header">
        <div>
          <p className="eyebrow">Coupons</p>
          <h1>쿠폰 관리</h1>
        </div>
      </div>

      <div className="form-page admin-form-page coupon-form-card">
        <h2>새 쿠폰 만들기</h2>
        <form onSubmit={handleSubmit} className="form">
          <label>
            쿠폰 코드 (영문/숫자/-/_ 3~30자, 대소문자 구분 없음)
            <input value={form.code} onChange={(e) => setField("code", e.target.value)} maxLength={30} required />
          </label>
          <div className="coupon-form-row">
            <label>
              할인 방식
              <select value={form.discountType} onChange={(e) => setField("discountType", e.target.value as DiscountType)}>
                <option value="PERCENT">퍼센트(%)</option>
                <option value="FLAT">정액(원)</option>
              </select>
            </label>
            <label>
              {form.discountType === "PERCENT" ? "할인율(%)" : "할인 금액(원)"}
              <input
                type="number"
                min={1}
                max={form.discountType === "PERCENT" ? 100 : undefined}
                value={form.discountValue}
                onChange={(e) => setField("discountValue", Number(e.target.value))}
                required
              />
            </label>
            <label>
              총 사용 가능 횟수
              <input
                type="number"
                min={1}
                max={1000000}
                value={form.maxUses}
                onChange={(e) => setField("maxUses", Number(e.target.value))}
                required
              />
            </label>
          </div>
          <div className="coupon-form-row">
            <label>
              사용 시작
              <input type="datetime-local" value={form.validFrom} onChange={(e) => setField("validFrom", e.target.value)} required />
            </label>
            <label>
              사용 종료
              <input type="datetime-local" value={form.validTo} onChange={(e) => setField("validTo", e.target.value)} required />
            </label>
          </div>
          {formError && <p className="form-error">{formError}</p>}
          <button type="submit" disabled={isSaving}>
            {isSaving ? "만드는 중..." : "쿠폰 만들기"}
          </button>
        </form>
      </div>

      {isLoading ? (
        <TextRowsSkeleton rows={3} />
      ) : loadError ? (
        <p className="form-error">{loadError}</p>
      ) : coupons.length === 0 ? (
        <p className="page-status">아직 만든 쿠폰이 없어요.</p>
      ) : (
        <div className="order-table-wrap">
        <table className="order-table admin-table">
          <thead>
            <tr>
              <th>코드</th>
              <th>혜택</th>
              <th>사용 기간</th>
              <th>사용 / 한도</th>
            </tr>
          </thead>
          <tbody>
            {coupons.map((coupon) => (
              <tr key={coupon.id}>
                <td className="num">{coupon.code}</td>
                <td>{describeDiscount(coupon)}</td>
                <td>
                  {new Date(coupon.validFrom).toLocaleDateString("ko-KR")} ~ {new Date(coupon.validTo).toLocaleDateString("ko-KR")}
                </td>
                <td className="num">
                  {coupon.usedCount} / {coupon.maxUses}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </div>
  );
}
