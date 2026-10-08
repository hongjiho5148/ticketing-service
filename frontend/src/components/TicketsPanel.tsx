import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchMyTickets } from "../api/tickets";
import { SeatLocationModal } from "./SeatLocationModal";
import { TextRowsSkeleton } from "./Skeleton";
import { TicketQrModal } from "./TicketQrModal";
import { extractErrorMessage } from "../utils/error";
import type { TicketHistoryItem, TicketStatus } from "../types";

const STATUS_LABEL: Record<TicketStatus, string> = {
  ISSUED: "입장 가능",
  USED: "입장 완료",
};

export function TicketsPanel() {
  const [tickets, setTickets] = useState<TicketHistoryItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [qrTicket, setQrTicket] = useState<TicketHistoryItem | null>(null);
  const [seatTicket, setSeatTicket] = useState<TicketHistoryItem | null>(null);

  useEffect(() => {
    fetchMyTickets()
      .then(setTickets)
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  if (isLoading) return <TextRowsSkeleton rows={4} />;
  if (error) return <p className="form-error">{error}</p>;

  return (
    <>
      {tickets.length === 0 ? (
        <p className="page-status">
          발급된 입장권이 없어요. 공연 시작 2시간 전부터 자동으로 발급돼요.{" "}
          <Link to="?tab=orders" replace className="seat-link">
            주문 내역 보기
          </Link>
        </p>
      ) : (
        <ul className="ticket-list">
          {tickets.map((ticket) => (
            <li key={ticket.ticketId} className="ticket-card">
              <div className="ticket-card-main">
                <h3>{ticket.eventTitle}</h3>
                <p className="ticket-card-meta">
                  {ticket.venue} · {new Date(ticket.eventStartAt).toLocaleString("ko-KR")}
                </p>
                <button type="button" className="seat-link" onClick={() => setSeatTicket(ticket)}>
                  {ticket.grade}석 · {ticket.section} · {ticket.seatNo}
                </button>
              </div>
              <div className="ticket-card-side">
                <span className={`ticket-status-badge status-${ticket.status.toLowerCase()}`}>
                  {STATUS_LABEL[ticket.status]}
                </span>
                {ticket.status === "ISSUED" && (
                  <button type="button" onClick={() => setQrTicket(ticket)}>
                    QR 보기
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {qrTicket && (
        <TicketQrModal orderId={qrTicket.orderId} eventTitle={qrTicket.eventTitle} onClose={() => setQrTicket(null)} />
      )}

      {seatTicket && (
        <SeatLocationModal
          eventId={seatTicket.eventId}
          eventTitle={seatTicket.eventTitle}
          venue={seatTicket.venue}
          section={seatTicket.section}
          rowNo={seatTicket.rowNo}
          seatNumber={seatTicket.seatNumber}
          onClose={() => setSeatTicket(null)}
        />
      )}
    </>
  );
}
