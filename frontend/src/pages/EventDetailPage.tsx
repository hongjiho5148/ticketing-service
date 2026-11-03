import { isAxiosError } from "axios";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { fetchEventDetail, fetchEventSeats } from "../api/events";
import { fetchQueueStatus } from "../api/queue";
import { cancelReservation, createReservation } from "../api/reservations";
import {
  applyCoupon,
  applyPoints,
  createOrder,
  fetchCheckout,
  createPaymentIntent,
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
import { HoldTimer, OpenCountdown, PassTimer } from "../components/Countdown";
import { VenueSeatMap } from "../components/VenueSeatMap";
import { SeatGrid } from "../components/SeatGrid";
import { QueueWaitingRoom } from "../components/QueueWaitingRoom";
import { dDayLabel, formatDateTime } from "../utils/date";
import { couponBenefitLabel } from "../utils/coupon";
import { posterGlyph, posterThemeClass } from "../utils/poster";
import { CATEGORY_LABEL } from "../utils/category";
import { availablePayMethods, requestPayment, type PayMethod } from "../utils/portone";
import type { ApiErrorBody, AvailableCoupon, EventDetail, Order, Reservation, ReviewListResponse, Seat } from "../types";

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
  // 잡아 둔 좌석을 결제 대기 주문에서 복원했으면 true다. 그런 방문은 대기열을 거치지 않았으므로(홀드 자체가 증거다)
  // 아래의 대기열/오픈 전/매진 관문이 적용되면 안 된다.
  const [resumed, setResumed] = useState(false);

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [passToken, setPassToken] = useState<string | null>(null);
  // 입장권이 끝나는 로컬 시계 시각이다. 서버가 알려 주지 않았으면 null이다.
  const [passDeadline, setPassDeadline] = useState<number | null>(null);
  const hadReservation = useRef(false);
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [selectedSeat, setSelectedSeat] = useState<Seat | null>(null);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);
  // 대기열 화면이 다시 보이는 이유다. 토스트는 3초면 사라지고, 오류 배너는 교체되는 페이지 위에 놓인다.
  const [queueNotice, setQueueNotice] = useState<string | null>(null);
  const [isConfirmingCancel, setIsConfirmingCancel] = useState(false);
  // 오픈 카운트다운이 0이 되면 설정해서, 서버 쪽 UPCOMING -> OPEN 상태 전환(다음 스위퍼 주기에야 반영된다)을
  // 기다리지 않고 페이지가 넘어가게 한다.
  const [openedByClock, setOpenedByClock] = useState(false);
  // 구매자 확인 체크박스다. 새 예약마다 다시 묻는다.
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

  // 좌석을 잡으면 입장권이 연장되지만 페이지는 물어봐야만 그것을 안다. 예약이 다시 없어지면(취소되었거나 홀드가 끝났다)
  // 연장이 반영될 시간을 잠깐 둔 뒤 입장권이 얼마나 남았는지 묻는다.
  useEffect(() => {
    const had = hadReservation.current;
    hadReservation.current = reservation !== null;
    if (reservation !== null) {
      setPassDeadline(null); // 연장되는 중이라 물어보기 전에는 알 수 없다. 오래된 시계를 보이느니 시계를 보이지 않는다
      return;
    }
    if (!had || !passToken) return;
    const token = passToken;
    const id = window.setTimeout(() => {
      fetchQueueStatus(token)
        .then((status) => {
          if (status.status === "PASSED" && status.passExpiresInSeconds != null) {
            setPassDeadline(Date.now() + status.passExpiresInSeconds * 1000);
          } else {
            handlePassExpired();
          }
        })
        .catch(() => handlePassExpired());
    }, 2000);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reservation, passToken]);

  function handlePassExpired() {
    // 대기열로 돌아간다. 입장권이 없어졌으므로 그것으로는 더는 좌석을 잡을 수 없다.
    setPassToken(null);
    setPassDeadline(null);
    setSelectedSeat(null);
    // 더 구체적인 사유(홀드가 끝나서 결제가 환불됐다)를 일반적인 사유로 덮어쓰지 않는다.
    setQueueNotice((prev) => prev ?? "입장 시간이 지났어요. 다시 줄을 서 주세요.");
  }

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

  // 브라우저에 네이티브 공유 시트가 있으면 그것을 쓴다(휴대폰, Safari, 최신 Chrome/Edge). 없으면
  // 링크를 복사한다. 공유 시트를 닫으면 AbortError로 reject되는데, 실패가 아니라 사용자의 선택이다.
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
      setQueueNotice(null);
    } catch (err) {
      if (isAxiosError<ApiErrorBody>(err) && err.response?.data?.code === "PASS_TOKEN_REQUIRED") {
        // 좌석을 고르는 동안 입장권이 끝났다. 갈 곳 없는 메시지 대신 대기열로 돌려보낸다.
        handlePassExpired();
        return;
      }
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

  // 이 순간에 좌석은 서버 쪽에서 스스로 다시 판매로 돌아간다. 이 함수는 화면을 거기에 맞출 뿐이다. 이미 진행 중인
  // 결제는 건드리지 않는다. 그 호출은 서버의 판정으로 끝난다.
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

  // 주문은 필요할 때 만든다. 처음 쿠폰/포인트를 적용할 때나 결제할 때 중 먼저 오는 때이고, 그 뒤에는 재사용한다
  // (예약 하나에는 주문이 하나만 있을 수 있다).
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

  // 목록에 있는 쿠폰 중 하나를 고르면 곧바로 적용한다. 코드를 다시 입력하지 않아도 된다.
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

  // 100은 카드 결제가 받아 주는 가장 작은 금액이다. 서버 자체의 상한과 같다.
  const maxPoints = Math.max(
    0,
    Math.min(pointBalance, (order?.originalPrice ?? selectedSeat?.price ?? 0) - (order?.discountAmount ?? 0) - 100),
  );

  async function handleCheckout() {
    if (!reservation || !selectedSeat || !event || !user) return;
    setError(null);
    setIsProcessing(true);
    try {
      // 재시도(예: PG 결제창을 닫았을 때)할 때 주문을 두 번째로 만들지 않고 재사용한다.
      // 예약 하나에는 주문이 하나만 있을 수 있다.
      const currentOrder = await ensureOrder();

      // 결제창이 열리기 전에 기록한다. 서버는 이것이 없으면 주문을 확정하지 않는다.
      await verifyIdentity(currentOrder.orderId);

      // 결제 id와 금액은 서버가 정한다. 서버는 자기가 발급한 id로 결제한 것만 받아들인다.
      const intent = await createPaymentIntent(currentOrder.orderId);

      const paymentResult = await requestPayment(
        {
          method: payMethod,
          orderName: event.title,
          totalAmount: intent.amount,
          customerName: user.name,
          customerEmail: user.email,
        },
        intent.paymentId,
      );

      if (paymentResult.failed) {
        setError(paymentResult.failureMessage ?? "결제가 취소됐어요. 다시 시도해주세요.");
        return;
      }

      // PG 팝업은 끝났다는 것만 알려 줄 뿐 실제로 성공했는지는 알려 주지 않는다. 백엔드가
      // PortOne 서버에서 다시 확인한 뒤에야 믿는다.
      const verified = await payOrder(currentOrder.orderId, paymentResult.paymentId);
      if (verified.paymentStatus === "SUCCESS") {
        navigate("/tickets?tab=orders");
        return;
      }

      const holdLost = verified.failureReason === "HOLD_EXPIRED";
      if (resumed) {
        showToast(
          holdLost
            ? "좌석 예약 시간이 지나 결제가 자동으로 환불됐어요. 다시 예약해주세요."
            : "결제가 거절됐어요. 좌석이 해제됐어요.",
          "error",
        );
        navigate("/tickets?tab=orders");
        return;
      }
      const lostMessage = holdLost
        ? "좌석 예약 시간이 지나 결제가 자동으로 환불됐어요. 다시 예약해주세요."
        : "결제가 거절됐어요. 좌석이 해제됐으니 다시 예약해주세요.";
      setError(lostMessage);
      // 이 시점에는 대기열 입장권이 대개 끝나 있어서 `error`가 보이지 않는 대기열 화면으로 돌아가게 된다.
      setQueueNotice(lostMessage);
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

  if (!user && (event.status === "CLOSED" || new Date(event.salesCloseAt).getTime() <= Date.now())) {
    // 아무도 더는 예매할 수 없는 공연에 로그인하라고 요구할 이유가 없다.
    return (
      <div className="queue-room">
        <div className="queue-card">
          <h2>예매가 마감된 공연이에요</h2>
          <p className="page-status">공연 시작 2시간 전({formatDateTime(event.salesCloseAt)})까지 예매할 수 있어요.</p>
        </div>
      </div>
    );
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

  // 아직(또는 더는) 예매할 것이 없다. 대기열을 건너뛰고 그에 맞는 알림을 권한다.
  const isPreOpen =
    event.status === "UPCOMING" && !openedByClock && new Date(event.openAt).getTime() > Date.now();
  const totalSeats = event.sectionSummary.reduce((sum, s) => sum + s.totalCount, 0);
  const availableSeats = event.sectionSummary.reduce((sum, s) => sum + s.availableCount, 0);
  const isSoldOut = event.status === "OPEN" && totalSeats > 0 && availableSeats === 0;
  // 좌석은 공연 두 시간 전까지 잡을 수 있다. 서버가 자기 시계로 이를 판정하고(그 뒤의 예매는 거절한다), 페이지는 어디로도
  // 이어지지 않을 대기열을 권하는 대신 그것을 알려 주기만 하면 된다. 이미 좌석을 잡았거나 앞선 주문을 마무리하는 사람은
  // 도중에 돌려보내지 않는다.
  const isSalesClosed = event.status === "CLOSED" || new Date(event.salesCloseAt).getTime() <= Date.now();

  if (isSalesClosed && !resumed && !reservation) {
    return (
      <div>
        {header}
        <div className="gate-panel">
          <div className="open-countdown">
            <span>현재</span>
            <strong>예매 마감</strong>
          </div>
          <p className="gate-panel-note">공연 시작 2시간 전({formatDateTime(event.salesCloseAt)})까지 예매할 수 있어요.</p>
        </div>
      </div>
    );
  }

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
    return (
      <QueueWaitingRoom
        eventId={event.id}
        notice={queueNotice}
        onPassed={(token, expiresInSeconds) => {
          setQueueNotice(null);
          setPassToken(token);
          setPassDeadline(expiresInSeconds != null ? Date.now() + expiresInSeconds * 1000 : null);
        }}
      />
    );
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
              {event.sourceUrl && (
                <p className="detail-source">
                  출처:{" "}
                  <a href={event.sourceUrl} target="_blank" rel="noreferrer noopener">
                    (재)예술경영지원센터 공연예술통합전산망, www.kopis.or.kr
                  </a>
                </p>
              )}
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
            <>
              {passDeadline !== null && !resumed && <PassTimer deadline={passDeadline} onExpire={handlePassExpired} />}
              <button type="button" onClick={handleReserve} disabled={!selectedSeat || isProcessing}>
                좌석 예약하기
              </button>
            </>
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
