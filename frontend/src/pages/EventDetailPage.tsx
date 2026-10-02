import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { fetchEventDetail, fetchEventSeats } from "../api/events";
import { cancelReservation, createReservation } from "../api/reservations";
import { createOrder, payOrder } from "../api/orders";
import { createReview, fetchReviews } from "../api/reviews";
import { addToWishlist, fetchWishlist, removeFromWishlist } from "../api/wishlist";
import { extractErrorMessage } from "../utils/error";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { VenueSeatMap } from "../components/VenueSeatMap";
import { SeatGrid } from "../components/SeatGrid";
import { QueueWaitingRoom } from "../components/QueueWaitingRoom";
import { dDayLabel, formatDateTime } from "../utils/date";
import { posterGlyph, posterThemeClass } from "../utils/poster";
import { requestCardPayment } from "../utils/portone";
import type { EventDetail, Order, Reservation, ReviewListResponse, Seat } from "../types";

const GRADE_ORDER = ["VIP", "R", "S"];

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const { user } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

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

  const [isWishlisted, setIsWishlisted] = useState(false);
  const [isTogglingWishlist, setIsTogglingWishlist] = useState(false);

  const [reviews, setReviews] = useState<ReviewListResponse | null>(null);
  const [reviewRating, setReviewRating] = useState(5);
  const [reviewContent, setReviewContent] = useState("");
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [isSubmittingReview, setIsSubmittingReview] = useState(false);

  useDocumentTitle(event ? event.title : "이벤트 상세");

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
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleCheckout() {
    if (!reservation || !selectedSeat || !event || !user) return;
    setError(null);
    setIsProcessing(true);
    try {
      // Re-use the order across retries (e.g. the PG payment window was cancelled) instead of
      // creating a second one - a reservation can only ever have a single order.
      const currentOrder = order ?? (await createOrder(reservation.reservationId));
      if (!order) setOrder(currentOrder);

      const paymentResult = await requestCardPayment({
        orderName: event.title,
        totalAmount: selectedSeat.price,
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
        navigate("/orders");
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

  if (!passToken) {
    return <QueueWaitingRoom eventId={event.id} onPassed={setPassToken} />;
  }

  return (
    <div>
      <Link to="/" className="detail-back">
        ← 목록으로
      </Link>

      <div className={`detail-hero ${posterThemeClass(event.id)}`}>
        <span className="poster-glyph">{posterGlyph(event.title)}</span>
        <div className="detail-hero-content">
          {event.status !== "CLOSED" && <span className="detail-hero-dday">{dDayLabel(event.startAt)}</span>}
          <div className="detail-hero-title-row">
            <h1>{event.title}</h1>
            <button
              type="button"
              className={`wishlist-toggle ${isWishlisted ? "active" : ""}`}
              onClick={toggleWishlist}
              disabled={isTogglingWishlist}
            >
              {isWishlisted ? "♥ 찜 완료" : "♡ 찜하기"}
            </button>
          </div>
          <div className="detail-hero-meta">
            <span>📍 {event.venue}</span>
            <span>🗓 {formatDateTime(event.startAt)}</span>
          </div>
        </div>
      </div>

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
              <button type="button" onClick={handleCheckout} disabled={isProcessing}>
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

          <p className="refund-policy">공연 시작 24시간 전까지 전액 환불 가능합니다.</p>
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
