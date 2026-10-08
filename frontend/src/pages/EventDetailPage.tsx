import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { fetchEventDetail, fetchEventSeats } from "../api/events";
import { cancelReservation, createReservation } from "../api/reservations";
import {
  applyCoupon,
  applyPoints,
  createOrder,
  fetchCheckout,
  payOrder,
  removeCoupon,
  verifyIdentity,
} from "../api/orders";
import { fetchAvailableCoupons } from "../api/coupons";
import { fetchPoints } from "../api/points";
import { createReview, fetchReviews } from "../api/reviews";
import { addToWishlist, fetchWishlist, removeFromWishlist } from "../api/wishlist";
import { extractErrorMessage } from "../utils/error";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { AlertSubscribeCard } from "../components/AlertSubscribeCard";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { HoldTimer, OpenCountdown } from "../components/Countdown";
import { VenueSeatMap } from "../components/VenueSeatMap";
import { SeatGrid } from "../components/SeatGrid";
import { QueueWaitingRoom } from "../components/QueueWaitingRoom";
import { dDayLabel, formatDateTime } from "../utils/date";
import { couponBenefitLabel } from "../utils/coupon";
import { posterGlyph, posterThemeClass } from "../utils/poster";
import { CATEGORY_LABEL } from "../utils/category";
import { availablePayMethods, requestPayment, type PayMethod } from "../utils/portone";
import type { AvailableCoupon, EventDetail, Order, Reservation, ReviewListResponse, Seat } from "../types";

const GRADE_ORDER = ["VIP", "R", "S"];

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const { user } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();
  // /events/:id?resume=<orderId> - arriving from "내 주문" to finish paying for a seat that is still held.
  const [searchParams] = useSearchParams();
  const resumeOrderId = searchParams.get("resume");
  const [resumeState, setResumeState] = useState<"none" | "loading" | "error">(resumeOrderId ? "loading" : "none");
  // True once a held seat was restored from a pending order. Such a visit never went through the queue (the hold
  // itself is the proof), so the queue/pre-open/sold-out gates below must not apply to it.
  const [resumed, setResumed] = useState(false);

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [passToken, setPassToken] = useState<string | null>(null);
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [selectedSeat, setSelectedSeat] = useState<Seat | null>(null);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isConfirmingCancel, setIsConfirmingCancel] = useState(false);
  // Set when the open countdown reaches zero, so the page moves on without waiting for the
  // server-side UPCOMING -> OPEN status flip (which only lands on the next sweeper tick).
  const [openedByClock, setOpenedByClock] = useState(false);
  // The buyer-confirmation checkbox; asked again for every new reservation.
  const [identityConfirmed, setIdentityConfirmed] = useState(false);

  const [isWishlisted, setIsWishlisted] = useState(false);
  const [isTogglingWishlist, setIsTogglingWishlist] = useState(false);

  const [reviews, setReviews] = useState<ReviewListResponse | null>(null);
  const [reviewRating, setReviewRating] = useState(5);
  const [reviewContent, setReviewContent] = useState("");
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [isSubmittingReview, setIsSubmittingReview] = useState(false);

  const payMethods = useMemo(() => availablePayMethods(), []);
  const [payMethod, setPayMethod] = useState<PayMethod>("CARD");

  const [pointBalance, setPointBalance] = useState(0);
  const [couponInput, setCouponInput] = useState("");
  const [availableCoupons, setAvailableCoupons] = useState<AvailableCoupon[]>([]);
  const [pointsInput, setPointsInput] = useState("");
  const [benefitError, setBenefitError] = useState<string | null>(null);
  const [isApplyingBenefit, setIsApplyingBenefit] = useState(false);

  useDocumentTitle(event ? event.title : "이벤트 상세");

  useEffect(() => {
    setIdentityConfirmed(false);
  }, [reservation?.reservationId]);

  useEffect(() => {
    if (!eventId) return;
    fetchEventDetail(Number(eventId))
      .then(setEvent)
      .catch((err) => setError(extractErrorMessage(err)));
    fetchEventSeats(Number(eventId))
      .then(setSeats)
      .catch((err) => setError(extractErrorMessage(err)));
    fetchReviews(Number(eventId)).then(setReviews).catch(() => undefined);
  }, [eventId]);

  useEffect(() => {
    if (!user || !eventId) return;
    fetchWishlist()
      .then((list) => setIsWishlisted(list.some((e) => e.id === Number(eventId))))
      .catch(() => undefined);
  }, [user, eventId]);

  // Native share sheet where the browser has one (phones, Safari, recent Chrome/Edge); otherwise
  // copy the link. Closing the share sheet rejects with AbortError - that's a choice, not a failure.
  async function handleShare() {
    if (!event) return;
    const url = `${window.location.origin}/events/${event.id}`;
    try {
      if (navigator.share) {
        await navigator.share({ title: event.title, text: `${event.title} - ${event.venue}`, url });
        return;
      }
      await navigator.clipboard.writeText(url);
      showToast("공연 링크가 복사됐어요.");
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      try {
        await navigator.clipboard.writeText(url);
        showToast("공연 링크가 복사됐어요.");
      } catch {
        showToast("링크를 복사하지 못했어요. 주소창의 주소를 직접 복사해주세요.", "error");
      }
    }
  }

  async function toggleWishlist() {
    if (!event) return;
    setIsTogglingWishlist(true);
    try {
      if (isWishlisted) {
        await removeFromWishlist(event.id);
        setIsWishlisted(false);
      } else {
        await addToWishlist(event.id);
        setIsWishlisted(true);
      }
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setIsTogglingWishlist(false);
    }
  }

  async function handleSubmitReview(e: FormEvent) {
    e.preventDefault();
    if (!event) return;
    setReviewError(null);
    setIsSubmittingReview(true);
    try {
      await createReview(event.id, reviewRating, reviewContent);
      setReviewContent("");
      setReviewRating(5);
      showToast("후기가 등록됐어요.");
      fetchReviews(event.id).then(setReviews).catch(() => undefined);
    } catch (err) {
      setReviewError(extractErrorMessage(err));
    } finally {
      setIsSubmittingReview(false);
    }
  }

  const gradeLegend = useMemo(() => {
    if (!event) return [];
    return GRADE_ORDER.map((grade) => event.sectionSummary.find((s) => s.grade === grade)).filter(
      (s): s is NonNullable<typeof s> => Boolean(s),
    );
  }, [event]);

  const sectionSeats = useMemo(
    () => (selectedSection ? seats.filter((seat) => seat.section === selectedSection) : []),
    [seats, selectedSection],
  );

  function handleSelectSeat(seat: Seat) {
    if (selectedSeat?.id === seat.id) {
      setSelectedSeat(null);
      return;
    }
    if (seat.status !== "AVAILABLE") return;
    setSelectedSeat(seat);
  }

  async function handleReserve() {
    if (!selectedSeat || !passToken) return;
    setError(null);
    setIsProcessing(true);
    try {
      const created = await createReservation(selectedSeat.id, passToken);
      setReservation(created);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleCancelReservation() {
    if (!reservation) return;
    setIsConfirmingCancel(false);
    setIsProcessing(true);
    try {
      await cancelReservation(reservation.reservationId);
      setReservation(null);
      setSelectedSeat(null);
      setOrder(null);
      showToast("예약이 취소됐어요.");
      if (resumed) navigate("/tickets?tab=orders");
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  useEffect(() => {
    if (!resumeOrderId || !event || seats.length === 0 || !user || resumeState !== "loading") return;
    let cancelled = false;
    fetchCheckout(Number(resumeOrderId))
      .then((checkout) => {
        if (cancelled) return;
        const seat = seats.find((s) => s.id === checkout.seatId);
        if (!seat || checkout.eventId !== event.id) {
          setError("이 공연의 주문이 아니에요.");
          setResumeState("error");
          return;
        }
        setSelectedSeat(seat);
        setSelectedSection(seat.section);
        setReservation({
          reservationId: checkout.order.reservationId,
          seatId: checkout.seatId,
          status: "HOLDING",
          holdExpireAt: checkout.holdExpireAt,
        });
        setOrder(checkout.order);
        setResumed(true);
        setResumeState("none");
      })
      .catch((err) => {
        if (cancelled) return;
        setError(extractErrorMessage(err));
        setResumeState("error");
      });
    return () => {
      cancelled = true;
    };
  }, [resumeOrderId, event, seats, user, resumeState]);

  // The seat goes back on sale server-side on its own at this moment; this just brings the screen
  // in line. A payment already in flight is left alone - that call settles with the server's verdict.
  function handleHoldExpired() {
    if (isProcessing) return;
    if (resumed) {
      showToast("결제 가능 시간이 지나 좌석이 해제됐어요.", "error");
      navigate("/tickets?tab=orders");
      return;
    }
    setReservation(null);
    setSelectedSeat(null);
    setOrder(null);
    setError("예약 시간이 지나 좌석이 해제됐어요. 좌석을 다시 선택해주세요.");
    fetchEventSeats(Number(eventId)).then(setSeats).catch(() => undefined);
  }

  useEffect(() => {
    if (!reservation) return;
    fetchPoints()
      .then((summary) => setPointBalance(summary.balance))
      .catch(() => undefined);
    fetchAvailableCoupons()
      .then(setAvailableCoupons)
      .catch(() => undefined);
  }, [reservation]);

  // The order is created lazily - at the first coupon/points application or at checkout, whichever
  // comes first - and reused after that (a reservation can only ever have one order).
  async function ensureOrder(): Promise<Order> {
    if (order) return order;
    if (!reservation) throw new Error("예약 정보가 없어요.");
    const created = await createOrder(reservation.reservationId);
    setOrder(created);
    return created;
  }

  async function runBenefit(action: (current: Order) => Promise<Order>, successMessage?: string): Promise<boolean> {
    setBenefitError(null);
    setIsApplyingBenefit(true);
    try {
      const updated = await action(await ensureOrder());
      setOrder(updated);
      if (successMessage) showToast(successMessage);
      return true;
    } catch (err) {
      setBenefitError(extractErrorMessage(err));
      return false;
    } finally {
      setIsApplyingBenefit(false);
    }
  }

  async function handleApplyCoupon() {
    const code = couponInput.trim();
    if (!code) return;
    if (await runBenefit((current) => applyCoupon(current.orderId, code), "쿠폰이 적용됐어요.")) {
      setCouponInput("");
    }
  }

  // Picking one of the listed coupons applies it straight away - no retyping the code.
  async function handleApplyCouponCode(code: string) {
    await runBenefit((current) => applyCoupon(current.orderId, code), "쿠폰이 적용됐어요.");
  }

  function handleApplyPoints() {
    const points = Number(pointsInput || 0);
    if (!Number.isInteger(points) || points < 0) {
      setBenefitError("포인트는 0 이상의 정수로 입력해주세요.");
      return;
    }
    runBenefit((current) => applyPoints(current.orderId, points), points > 0 ? "포인트가 적용됐어요." : undefined);
  }

  // 100 is the smallest amount the card payment accepts - mirrors the server's own cap.
  const maxPoints = Math.max(
    0,
    Math.min(pointBalance, (order?.originalPrice ?? selectedSeat?.price ?? 0) - (order?.discountAmount ?? 0) - 100),
  );

  async function handleCheckout() {
    if (!reservation || !selectedSeat || !event || !user) return;
    setError(null);
    setIsProcessing(true);
    try {
      // Re-use the order across retries (e.g. the PG payment window was cancelled) instead of
      // creating a second one - a reservation can only ever have a single order.
      const currentOrder = await ensureOrder();

      // Recorded before the payment window opens - the server won't confirm an order without it.
      await verifyIdentity(currentOrder.orderId);

      const paymentResult = await requestPayment({
        method: payMethod,
        orderName: event.title,
        totalAmount: currentOrder.totalPrice,
        customerName: user.name,
        customerEmail: user.email,
      });

      if (paymentResult.failed) {
        setError(paymentResult.failureMessage ?? "결제가 취소됐어요. 다시 시도해주세요.");
        return;
      }

      // The PG popup only tells us it finished, not whether it actually succeeded - the backend
      // re-checks with PortOne's server before trusting it.
      const verified = await payOrder(currentOrder.orderId, paymentResult.paymentId);
      if (verified.paymentStatus === "SUCCESS") {
        navigate("/tickets?tab=orders");
        return;
      }

      if (resumed) {
        showToast("결제가 거절됐어요. 좌석이 해제됐어요.", "error");
        navigate("/tickets?tab=orders");
        return;
      }
      setError("결제가 거절됐어요. 좌석이 해제됐으니 다시 예약해주세요.");
      setReservation(null);
      setSelectedSeat(null);
      setOrder(null);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  if (!event) {
    return <p className="page-status">{error ?? "불러오는 중..."}</p>;
  }

  if (!user) {
    return (
      <div className="queue-room">
        <div className="queue-card">
          <h2>로그인이 필요합니다</h2>
          <p className="page-status">이 공연 예매 대기열에 입장하려면 먼저 로그인해주세요.</p>
          <button type="button" onClick={() => navigate("/login")}>
            로그인하러 가기
          </button>
        </div>
      </div>
    );
  }

  if (resumeOrderId && resumeState === "loading") {
    return <p className="page-status">결제 정보를 불러오는 중...</p>;
  }

  if (resumeOrderId && resumeState === "error") {
    return (
      <div className="form-page">
        <h1>결제를 이어갈 수 없어요</h1>
        <p className="form-error">{error}</p>
        <p>
          <Link to="/tickets?tab=orders">내 주문으로 돌아가기</Link>
        </p>
      </div>
    );
  }

  const header = (
    <>
      <Link to="/" className="detail-back">
        ← 목록으로
      </Link>

      <div className={`detail-hero ${posterThemeClass(event.id)}`}>
        <span className="poster-glyph">{posterGlyph(event.title)}</span>
        <div className="detail-hero-content">
          {event.status !== "CLOSED" && <span className="detail-hero-dday">{dDayLabel(event.startAt)}</span>}
          <div className="detail-hero-title-row">
            <h1>{event.title}</h1>
            <div className="detail-hero-actions">
              <button type="button" className="wishlist-toggle" onClick={handleShare}>
                ↗ 공유
              </button>
              <button
                type="button"
                className={`wishlist-toggle ${isWishlisted ? "active" : ""}`}
                onClick={toggleWishlist}
                disabled={isTogglingWishlist}
              >
                {isWishlisted ? "♥ 찜 완료" : "♡ 찜하기"}
              </button>
            </div>
          </div>
          <div className="detail-hero-meta">
            <span>🎫 {CATEGORY_LABEL[event.category]}</span>
            <span>📍 {event.venue}</span>
            <span>🗓 {formatDateTime(event.startAt)}</span>
            {event.averageRating !== null && (
              <span>
                ★ {event.averageRating.toFixed(1)} ({event.reviewCount})
              </span>
            )}
          </div>
        </div>
      </div>
    </>
  );

  // Nothing to book yet / anymore: skip the queue and offer the matching alert instead.
  const isPreOpen =
    event.status === "UPCOMING" && !openedByClock && new Date(event.openAt).getTime() > Date.now();
  const totalSeats = event.sectionSummary.reduce((sum, s) => sum + s.totalCount, 0);
  const availableSeats = event.sectionSummary.reduce((sum, s) => sum + s.availableCount, 0);
  const isSoldOut = event.status === "OPEN" && totalSeats > 0 && availableSeats === 0;

  if (isPreOpen && !resumed) {
    return (
      <div>
        {header}
        <div className="gate-panel">
          <OpenCountdown openAt={event.openAt} onOpen={() => setOpenedByClock(true)} />
          <p className="gate-panel-note">{formatDateTime(event.openAt)}에 예매가 시작돼요.</p>
          <AlertSubscribeCard eventId={event.id} kind="open-alert" />
        </div>
      </div>
    );
  }

  if (isSoldOut && !resumed) {
    return (
      <div>
        {header}
        <div className="gate-panel">
          <div className="open-countdown">
            <span>현재</span>
            <strong>매진</strong>
          </div>
          <p className="gate-panel-note">결제되지 않은 좌석은 5분 뒤 다시 풀려요. 취소표가 나오면 알려드릴게요.</p>
          <AlertSubscribeCard eventId={event.id} kind="waitlist" />
        </div>
      </div>
    );
  }

  if (!passToken && !resumed) {
    return <QueueWaitingRoom eventId={event.id} onPassed={setPassToken} />;
  }

  return (
    <div>
      {header}

      <div className="detail-layout">
        <div>
          {event.description && (
            <>
              <h2 className="detail-section-title">공연 소개</h2>
              <p className="detail-description">{event.description}</p>
            </>
          )}

          <h2 className="detail-section-title">좌석 등급 안내</h2>
          <div className="grade-legend">
            {gradeLegend.map((g) => (
              <div key={g.grade} className="grade-legend-item">
                <span className={`grade-dot grade-swatch-${g.grade}`} />
                {g.grade}석<span className="grade-legend-price">{g.price.toLocaleString()}원</span>
              </div>
            ))}
          </div>

          {error && <p className="form-error">{error}</p>}

          <VenueSeatMap
            sections={event.sectionSummary}
            selectedSection={selectedSection}
            onSelectSection={(section) => setSelectedSection(section)}
          />

          {selectedSection && (
            <SeatGrid
              section={selectedSection}
              seats={sectionSeats}
              selectedSeatId={selectedSeat?.id ?? null}
              onSelectSeat={handleSelectSeat}
              onClose={() => setSelectedSection(null)}
            />
          )}
        </div>

        <div className="booking-panel">
          <h2>{event.title}</h2>
          <p className="booking-panel-venue">{formatDateTime(event.startAt)}</p>

          <div className="booking-selection">
            {selectedSeat ? (
              <>
                <div className="booking-selection-row">
                  <span>구역</span>
                  <span>{selectedSeat.section}</span>
                </div>
                <div className="booking-selection-row">
                  <span>좌석</span>
                  <span>{selectedSeat.seatNo}</span>
                </div>
                <div className="booking-selection-row">
                  <span>등급</span>
                  <span>{selectedSeat.grade}석</span>
                </div>
                <div className="booking-selection-row">
                  <span>결제 금액</span>
                  <span>{selectedSeat.price.toLocaleString()}원</span>
                </div>
              </>
            ) : (
              <p className="booking-selection-empty">좌석맵에서 구역을 선택해주세요</p>
            )}
          </div>

          {!reservation ? (
            <button type="button" onClick={handleReserve} disabled={!selectedSeat || isProcessing}>
              좌석 예약하기
            </button>
          ) : (
            <div className="reservation-panel">
              <p>
                예약 완료 — {new Date(reservation.holdExpireAt).toLocaleTimeString("ko-KR")}까지 결제해주세요.
              </p>
              <HoldTimer key={reservation.reservationId} expireAt={reservation.holdExpireAt} onExpire={handleHoldExpired} />

              <div className="benefit-box">
                <div className="benefit-row">
                  <input
                    placeholder="쿠폰 코드"
                    value={couponInput}
                    onChange={(e) => setCouponInput(e.target.value)}
                    maxLength={30}
                    disabled={isApplyingBenefit}
                  />
                  <button type="button" className="btn-secondary" onClick={handleApplyCoupon} disabled={isApplyingBenefit || !couponInput.trim()}>
                    적용
                  </button>
                </div>
                {availableCoupons.length > 0 && !order?.couponCode && (
                  <div className="coupon-chips">
                    {availableCoupons.map((c) => (
                      <button
                        key={c.code}
                        type="button"
                        className="coupon-chip"
                        onClick={() => handleApplyCouponCode(c.code)}
                        disabled={isApplyingBenefit}
                      >
                        <strong>{c.code}</strong>
                        <span>{couponBenefitLabel(c)}</span>
                      </button>
                    ))}
                  </div>
                )}
                <div className="benefit-row">
                  <input
                    type="number"
                    min={0}
                    max={maxPoints}
                    placeholder={`포인트 (보유 ${pointBalance.toLocaleString()}P)`}
                    value={pointsInput}
                    onChange={(e) => setPointsInput(e.target.value)}
                    disabled={isApplyingBenefit}
                  />
                  <button type="button" className="btn-secondary" onClick={() => setPointsInput(String(maxPoints))} disabled={isApplyingBenefit || maxPoints === 0}>
                    최대
                  </button>
                  <button type="button" className="btn-secondary" onClick={handleApplyPoints} disabled={isApplyingBenefit}>
                    적용
                  </button>
                </div>
                {benefitError && <p className="form-error">{benefitError}</p>}
              </div>

              {order && (
                <div className="price-summary">
                  <div className="booking-selection-row">
                    <span>상품 금액</span>
                    <span>{order.originalPrice.toLocaleString()}원</span>
                  </div>
                  {order.couponCode && (
                    <div className="booking-selection-row">
                      <span>
                        쿠폰 ({order.couponCode}){" "}
                        <button
                          type="button"
                          className="btn-link-inline"
                          onClick={() => runBenefit((current) => removeCoupon(current.orderId))}
                          disabled={isApplyingBenefit}
                        >
                          해제
                        </button>
                      </span>
                      <span>-{order.discountAmount.toLocaleString()}원</span>
                    </div>
                  )}
                  {order.pointsUsed > 0 && (
                    <div className="booking-selection-row">
                      <span>포인트</span>
                      <span>-{order.pointsUsed.toLocaleString()}원</span>
                    </div>
                  )}
                  <div className="booking-selection-row price-summary-total">
                    <span>결제 금액</span>
                    <span>{order.totalPrice.toLocaleString()}원</span>
                  </div>
                </div>
              )}

              {payMethods.length > 1 && (
                <div className="pay-method-group" role="radiogroup" aria-label="결제 수단">
                  {payMethods.map(({ method, label }) => (
                    <label key={method} className={`pay-method ${payMethod === method ? "selected" : ""}`}>
                      <input
                        type="radio"
                        name="payMethod"
                        value={method}
                        checked={payMethod === method}
                        onChange={() => setPayMethod(method)}
                      />
                      {label}
                    </label>
                  ))}
                </div>
              )}

              <label className="identity-check">
                <input
                  type="checkbox"
                  checked={identityConfirmed}
                  onChange={(e) => setIdentityConfirmed(e.target.checked)}
                  disabled={isProcessing}
                />
                <span>
                  본인 명의로 직접 결제하며, 재판매·대리 구매 목적이 아님을 확인합니다.
                  <small>본인인증(PASS 등) 연동 전이라 확인 동의로 대신하고, 동의 내용은 주문에 기록돼요.</small>
                </span>
              </label>
              <button
                type="button"
                onClick={handleCheckout}
                disabled={isProcessing || isApplyingBenefit || !identityConfirmed}
              >
                주문 및 결제하기
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => setIsConfirmingCancel(true)}
                disabled={isProcessing}
              >
                예약 취소
              </button>
            </div>
          )}

          <p className="refund-policy">
            취소 환불: 공연 7일 전까지 100%, 3일 전까지 70%, 1일 전까지 30%. 공연 24시간 전부터는 취소할 수 없어요.
          </p>
        </div>
      </div>

      <section className="detail-reviews">
        <h2 className="detail-section-title">
          관람 후기
          {reviews?.averageRating != null && ` · 평균 ${reviews.averageRating.toFixed(1)}점`}
        </h2>
        <form onSubmit={handleSubmitReview} className="form review-form">
          <label>
            평점
            <select value={reviewRating} onChange={(e) => setReviewRating(Number(e.target.value))}>
              {[5, 4, 3, 2, 1].map((n) => (
                <option key={n} value={n}>
                  {n}점
                </option>
              ))}
            </select>
          </label>
          <label>
            후기
            <textarea
              value={reviewContent}
              onChange={(e) => setReviewContent(e.target.value)}
              maxLength={1000}
              rows={3}
              placeholder="관람 경험을 남겨주세요 (결제 완료한 공연만 작성 가능해요)"
              required
            />
          </label>
          {reviewError && <p className="form-error">{reviewError}</p>}
          <button type="submit" disabled={isSubmittingReview}>
            {isSubmittingReview ? "등록 중..." : "후기 작성"}
          </button>
        </form>

        {reviews && reviews.content.length > 0 ? (
          <ul className="review-list">
            {reviews.content.map((review) => (
              <li key={review.id} className="review-item">
                <span className="review-rating">{"★".repeat(review.rating)}{"☆".repeat(5 - review.rating)}</span>
                <p>{review.content}</p>
                <span className="review-date">{new Date(review.createdAt).toLocaleDateString("ko-KR")}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="page-status">아직 후기가 없어요.</p>
        )}
      </section>

      {isConfirmingCancel && (
        <ConfirmDialog
          title="예약 취소"
          message="좌석 예약을 취소할까요?"
          confirmLabel="취소하기"
          danger
          onConfirm={handleCancelReservation}
          onCancel={() => setIsConfirmingCancel(false)}
        />
      )}
    </div>
  );
}
