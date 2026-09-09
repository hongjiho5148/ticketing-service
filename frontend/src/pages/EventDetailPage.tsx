import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchEventDetail, fetchEventSeats } from "../api/events";
import { cancelReservation, createReservation } from "../api/reservations";
import { createOrder, payOrder } from "../api/orders";
import { extractErrorMessage } from "../utils/error";
import { useAuth } from "../context/AuthContext";
import type { EventDetail, Reservation, Seat } from "../types";

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [event, setEvent] = useState<EventDetail | null>(null);
  const [selectedGrade, setSelectedGrade] = useState<string | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);

  useEffect(() => {
    if (!eventId) return;
    fetchEventDetail(Number(eventId))
      .then((detail) => {
        setEvent(detail);
        setSelectedGrade(detail.seatSummary[0]?.grade ?? null);
      })
      .catch((err) => setError(extractErrorMessage(err)));
  }, [eventId]);

  useEffect(() => {
    if (!eventId || !selectedGrade) return;
    fetchEventSeats(Number(eventId), selectedGrade)
      .then(setSeats)
      .catch((err) => setError(extractErrorMessage(err)));
  }, [eventId, selectedGrade]);

  async function handleReserve(seatId: number) {
    if (!user) {
      navigate("/login");
      return;
    }
    setError(null);
    setIsProcessing(true);
    try {
      const created = await createReservation(seatId);
      setReservation(created);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleCancelReservation() {
    if (!reservation) return;
    setIsProcessing(true);
    try {
      await cancelReservation(reservation.reservationId);
      setReservation(null);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleCheckout() {
    if (!reservation) return;
    setError(null);
    setIsProcessing(true);
    try {
      const order = await createOrder(reservation.reservationId);
      await payOrder(order.orderId);
      navigate("/orders");
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsProcessing(false);
    }
  }

  if (!event) {
    return <p className="page-status">{error ?? "불러오는 중..."}</p>;
  }

  return (
    <div>
      <h1>{event.title}</h1>
      <p>{event.venue}</p>
      <p>{new Date(event.startAt).toLocaleString()}</p>

      <div className="grade-tabs">
        {event.seatSummary.map((summary) => (
          <button
            key={summary.grade}
            type="button"
            className={summary.grade === selectedGrade ? "grade-tab active" : "grade-tab"}
            onClick={() => setSelectedGrade(summary.grade)}
          >
            {summary.grade} ({summary.availableCount}/{summary.totalCount})
          </button>
        ))}
      </div>

      {error && <p className="form-error">{error}</p>}

      <ul className="seat-list">
        {seats.map((seat) => (
          <li key={seat.id} className={`seat-item seat-${seat.status.toLowerCase()}`}>
            <span>{seat.seatNo}</span>
            <span>{seat.price.toLocaleString()}원</span>
            <button
              type="button"
              disabled={seat.status !== "AVAILABLE" || isProcessing}
              onClick={() => handleReserve(seat.id)}
            >
              예약하기
            </button>
          </li>
        ))}
      </ul>

      {reservation && (
        <div className="reservation-panel">
          <p>
            좌석 예약 완료 (예약 ID: {reservation.reservationId}) — {new Date(reservation.holdExpireAt).toLocaleTimeString()}까지
            결제해주세요.
          </p>
          <button type="button" onClick={handleCheckout} disabled={isProcessing}>
            주문 및 결제하기
          </button>
          <button type="button" onClick={handleCancelReservation} disabled={isProcessing}>
            예약 취소
          </button>
        </div>
      )}
    </div>
  );
}
