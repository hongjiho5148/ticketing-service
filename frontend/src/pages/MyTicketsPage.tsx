import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { fetchMyTicket, fetchMyTickets } from "../api/tickets";
import { SeatLocationModal } from "../components/SeatLocationModal";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { TicketHistoryItem, TicketStatus } from "../types";

const STATUS_LABEL: Record<TicketStatus, string> = {
  ISSUED: "입장 가능",
  USED: "입장 완료",
};

export function MyTicketsPage() {
  useDocumentTitle("내 티켓함");

  const [tickets, setTickets] = useState<TicketHistoryItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [qrTicket, setQrTicket] = useState<TicketHistoryItem | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [qrError, setQrError] = useState<string | null>(null);
  const [loadingQrFor, setLoadingQrFor] = useState<number | null>(null);

  const [seatTicket, setSeatTicket] = useState<TicketHistoryItem | null>(null);

  useEffect(() => {
    fetchMyTickets()
      .then(setTickets)
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  async function handleShowQr(ticket: TicketHistoryItem) {
    setQrError(null);
    setLoadingQrFor(ticket.ticketId);
    try {
      const detail = await fetchMyTicket(ticket.orderId);
      const dataUrl = await QRCode.toDataURL(detail.qrToken, { width: 240 });
      setQrDataUrl(dataUrl);
      setQrTicket(ticket);
    } catch (err) {
      setQrError(extractErrorMessage(err));
      setQrTicket(ticket);
    } finally {
      setLoadingQrFor(null);
    }
  }

  function closeQrModal() {
    setQrTicket(null);
    setQrDataUrl(null);
    setQrError(null);
  }

  if (isLoading) {
    return (
      <div>
        <h1>내 티켓함</h1>
        <TextRowsSkeleton rows={4} />
      </div>
    );
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <h1>내 티켓함</h1>
      {tickets.length === 0 ? (
        <p className="page-status">발급된 입장권이 없어요. 공연 시작 2시간 전부터 자동으로 발급됩니다.</p>
      ) : (
        <ul className="ticket-list">
          {tickets.map((ticket) => (
            <li key={ticket.ticketId} className="ticket-card">
              <div className="ticket-card-main">
                <h3>{ticket.eventTitle}</h3>
                <p className="ticket-card-meta">
                  {ticket.venue} · {new Date(ticket.eventStartAt).toLocaleString("ko-KR")}
                </p>
                <button
                  type="button"
                  className="seat-link"
                  onClick={() => setSeatTicket(ticket)}
                >
                  {ticket.grade}석 · {ticket.section} · {ticket.seatNo}
                </button>
              </div>
              <div className="ticket-card-side">
                <span className={`ticket-status-badge status-${ticket.status.toLowerCase()}`}>
                  {STATUS_LABEL[ticket.status]}
                </span>
                {ticket.status === "ISSUED" && (
                  <button
                    type="button"
                    onClick={() => handleShowQr(ticket)}
                    disabled={loadingQrFor === ticket.ticketId}
                  >
                    {loadingQrFor === ticket.ticketId ? "불러오는 중..." : "QR 보기"}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {qrTicket && (
        <div className="modal-overlay" onClick={closeQrModal}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>입장권 QR</h3>
                <p className="modal-subtitle">{qrTicket.eventTitle}</p>
              </div>
              <button type="button" className="btn-secondary" onClick={closeQrModal}>
                닫기
              </button>
            </div>
            {qrError ? (
              <p className="form-error">{qrError}</p>
            ) : qrDataUrl ? (
              <div className="ticket-qr">
                <img src={qrDataUrl} alt="입장권 QR 코드" width={240} height={240} />
                <p className="page-status">입구에서 이 QR을 보여주세요.</p>
              </div>
            ) : (
              <p className="page-status">불러오는 중...</p>
            )}
          </div>
        </div>
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
    </div>
  );
}
