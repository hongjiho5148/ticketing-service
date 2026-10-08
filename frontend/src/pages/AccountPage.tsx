import { useEffect, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { changePassword, updateProfile } from "../api/auth";
import { fetchNotificationPreference, updateNotificationPreference } from "../api/notifications";
import { fetchAvailableCoupons, fetchCouponHistory } from "../api/coupons";
import { fetchPoints } from "../api/points";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { couponBenefitLabel, couponExpiryLabel } from "../utils/coupon";
import { extractErrorMessage } from "../utils/error";
import { getPasswordStrength } from "../utils/passwordStrength";
import type { AvailableCoupon, CouponHistoryItem, PointSummary, PointTransactionType } from "../types";

const POINT_TYPE_LABEL: Record<PointTransactionType, string> = {
  EARN: "구매 적립",
  USE: "결제 사용",
  RESTORE: "취소 환원",
  CLAWBACK: "적립 회수",
};

const PROVIDER_LABEL: Record<string, string> = {
  LOCAL: "이메일",
  GOOGLE: "Google",
  KAKAO: "카카오",
};

const TABS = [
  { key: "profile", label: "회원정보" },
  { key: "security", label: "비밀번호" },
  { key: "notifications", label: "알림 설정" },
  { key: "points", label: "포인트" },
  { key: "coupons", label: "쿠폰" },
] as const;

export function AccountPage() {
  const { user, updateUser } = useAuth();
  const { showToast } = useToast();
  useDocumentTitle("마이페이지");
  const [searchParams] = useSearchParams();
  const tab = TABS.find((t) => t.key === searchParams.get("tab"))?.key ?? "profile";

  const [name, setName] = useState(user?.name ?? "");
  const [profileError, setProfileError] = useState<string | null>(null);
  const [isSavingProfile, setIsSavingProfile] = useState(false);

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmNewPassword, setConfirmNewPassword] = useState("");
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [isSavingPassword, setIsSavingPassword] = useState(false);
  const passwordStrength = getPasswordStrength(newPassword);

  const [points, setPoints] = useState<PointSummary | null>(null);
  const [coupons, setCoupons] = useState<CouponHistoryItem[]>([]);
  const [availableCoupons, setAvailableCoupons] = useState<AvailableCoupon[]>([]);

  useEffect(() => {
    fetchPoints().then(setPoints).catch(() => undefined);
    fetchCouponHistory().then(setCoupons).catch(() => undefined);
    fetchAvailableCoupons().then(setAvailableCoupons).catch(() => undefined);
  }, []);

  const [emailOptIn, setEmailOptIn] = useState(true);
  const [isSavingNotificationPref, setIsSavingNotificationPref] = useState(false);

  useEffect(() => {
    fetchNotificationPreference()
      .then((pref) => setEmailOptIn(pref.emailOptIn))
      .catch(() => undefined);
  }, []);

  if (!user) return null;

  async function handleToggleEmailOptIn() {
    const next = !emailOptIn;
    setIsSavingNotificationPref(true);
    try {
      const pref = await updateNotificationPreference(next);
      setEmailOptIn(pref.emailOptIn);
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setIsSavingNotificationPref(false);
    }
  }

  async function handleProfileSubmit(e: FormEvent) {
    e.preventDefault();
    setProfileError(null);
    setIsSavingProfile(true);
    try {
      const updated = await updateProfile(name);
      updateUser(updated);
      showToast("회원정보가 수정됐어요.");
    } catch (err) {
      setProfileError(extractErrorMessage(err));
    } finally {
      setIsSavingProfile(false);
    }
  }

  async function handlePasswordSubmit(e: FormEvent) {
    e.preventDefault();
    setPasswordError(null);

    if (!passwordStrength.isValid) {
      setPasswordError("새 비밀번호는 영문 대/소문자, 숫자, 특수문자를 모두 포함해 8자 이상으로 입력해주세요.");
      return;
    }
    if (newPassword !== confirmNewPassword) {
      setPasswordError("새 비밀번호가 일치하지 않습니다.");
      return;
    }

    setIsSavingPassword(true);
    try {
      await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmNewPassword("");
      showToast("비밀번호가 변경됐어요.");
    } catch (err) {
      setPasswordError(extractErrorMessage(err));
    } finally {
      setIsSavingPassword(false);
    }
  }

  return (
    <div className="account-page">
      <div className="page-head">
        <div>
          <p className="eyebrow">My page</p>
          <h1>마이페이지</h1>
        </div>
      </div>

      <div className="account-layout">
        <nav className="account-nav" aria-label="마이페이지 메뉴">
          {TABS.map((t) => (
            <Link
              key={t.key}
              to={`?tab=${t.key}`}
              replace
              className={t.key === tab ? "active" : undefined}
              aria-current={t.key === tab ? "page" : undefined}
            >
              <span>{t.label}</span>
              {t.key === "points" && points && <small className="num">{points.balance.toLocaleString()}P</small>}
              {t.key === "coupons" && availableCoupons.length > 0 && (
                <small className="num">{availableCoupons.length}장</small>
              )}
            </Link>
          ))}
        </nav>

        <div className="account-panel">
          {tab === "profile" && (
            <section className="account-section">
              <h2>회원정보</h2>
              <form onSubmit={handleProfileSubmit} className="form">
                <label>
                  이메일
                  <input value={user.email ?? "-"} disabled />
                </label>
                <label>
                  가입 방식
                  <input value={PROVIDER_LABEL[user.provider] ?? user.provider} disabled />
                </label>
                <label>
                  이름
                  <input value={name} onChange={(e) => setName(e.target.value)} required />
                </label>
                {profileError && <p className="form-error">{profileError}</p>}
                <button type="submit" disabled={isSavingProfile || name === user.name}>
                  {isSavingProfile ? "저장 중..." : "이름 저장"}
                </button>
              </form>
            </section>
          )}

          {tab === "security" &&
            (user.provider === "LOCAL" ? (
              <section className="account-section">
                <h2>비밀번호 변경</h2>
                <form onSubmit={handlePasswordSubmit} className="form">
                  <label>
                    현재 비밀번호
                    <input
                      type="password"
                      value={currentPassword}
                      onChange={(e) => setCurrentPassword(e.target.value)}
                      required
                    />
                  </label>
                  <label>
                    새 비밀번호
                    <input
                      type="password"
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      minLength={8}
                      required
                    />
                  </label>
                  {newPassword.length > 0 && (
                    <div className="password-strength">
                      <div className={`password-strength-bar level-${passwordStrength.level}`}>
                        <div
                          className="password-strength-bar-fill"
                          style={{ width: `${(passwordStrength.metCount / 5) * 100}%` }}
                        />
                      </div>
                      <p className={`password-strength-label level-${passwordStrength.level}`}>
                        보안 상태: {passwordStrength.label}
                      </p>
                      <ul className="password-requirements">
                        {passwordStrength.requirements.map((req) => (
                          <li key={req.key} className={req.met ? "met" : ""}>
                            {req.met ? "✓" : "·"} {req.label}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                  <label>
                    새 비밀번호 확인
                    <input
                      type="password"
                      value={confirmNewPassword}
                      onChange={(e) => setConfirmNewPassword(e.target.value)}
                      minLength={8}
                      required
                    />
                  </label>
                  {passwordError && <p className="form-error">{passwordError}</p>}
                  <button type="submit" disabled={isSavingPassword}>
                    {isSavingPassword ? "변경 중..." : "비밀번호 변경"}
                  </button>
                </form>
              </section>
            ) : (
              <section className="account-section">
                <h2>비밀번호 변경</h2>
                <p className="form-notice">
                  {PROVIDER_LABEL[user.provider] ?? user.provider} 계정으로 로그인 중이라 비밀번호를 별도로 관리하지
                  않아요.
                </p>
              </section>
            ))}

          {tab === "notifications" && (
            <section className="account-section">
              <h2>알림 설정</h2>
              <label className="notification-pref-row">
                <input
                  type="checkbox"
                  checked={emailOptIn}
                  onChange={handleToggleEmailOptIn}
                  disabled={isSavingNotificationPref}
                />
                공연 임박 알림, QR 발급 알림을 이메일로 받기
              </label>
            </section>
          )}

          {tab === "points" && (
            <section className="account-section">
              <h2>포인트</h2>
              <p className="wallet-balance">
                보유 포인트 <strong>{(points?.balance ?? 0).toLocaleString()}P</strong>
              </p>
              <p className="wallet-hint">결제 금액의 1%가 적립되고, 결제할 때 1P = 1원으로 쓸 수 있어요.</p>
              {points && points.transactions.length > 0 ? (
                <ul className="wallet-list">
                  {points.transactions.map((tx, index) => (
                    <li key={index}>
                      <span>
                        {POINT_TYPE_LABEL[tx.type]}
                        <small>
                          {" "}
                          · 주문 #{tx.orderId} · {new Date(tx.createdAt).toLocaleDateString("ko-KR")}
                        </small>
                      </span>
                      <strong className={tx.delta > 0 ? "plus" : "minus"}>
                        {tx.delta > 0 ? "+" : ""}
                        {tx.delta.toLocaleString()}P
                      </strong>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="wallet-hint">아직 포인트 내역이 없어요.</p>
              )}
            </section>
          )}

          {tab === "coupons" && (
            <>
              <section className="account-section">
                <h2>사용 가능한 쿠폰</h2>
                {availableCoupons.length > 0 ? (
                  <>
                    <ul className="wallet-list">
                      {availableCoupons.map((c) => (
                        <li key={c.code}>
                          <span>
                            {c.code}
                            <small> · {couponExpiryLabel(c.validTo)}</small>
                          </span>
                          <strong className="plus">{couponBenefitLabel(c)}</strong>
                        </li>
                      ))}
                    </ul>
                    <p className="wallet-hint">
                      쿠폰은 결제 화면에서 눌러 바로 적용할 수 있어요. 계정당 한 번씩 쓸 수 있어요.
                    </p>
                  </>
                ) : (
                  <p className="wallet-hint">지금 사용할 수 있는 쿠폰이 없어요.</p>
                )}
              </section>

              <section className="account-section">
                <h2>쿠폰 사용 내역</h2>
                {coupons.length > 0 ? (
                  <ul className="wallet-list">
                    {coupons.map((c) => (
                      <li key={`${c.code}-${c.orderId}`}>
                        <span>
                          {c.code}
                          <small>
                            {" "}
                            · 주문 #{c.orderId} · {new Date(c.redeemedAt).toLocaleDateString("ko-KR")}
                          </small>
                        </span>
                        <strong className="minus">-{c.discountAmount.toLocaleString()}원</strong>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="wallet-hint">아직 사용한 쿠폰이 없어요.</p>
                )}
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
