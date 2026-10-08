import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { fetchMyTicket } from "../api/tickets";
import { extractErrorMessage } from "../utils/error";

interface TicketQrModalProps {
  orderId: number;
  eventTitle: string;
  onClose: () => void;
}

// Fetches the signed QR token for one order and renders it; shared by the ticket and order cards.
export function TicketQrModal({ orderId, eventTitle, onClose }: TicketQrModalProps) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMyTicket(orderId)
      .then((detail) => QRCode.toDataURL(detail.qrToken, { width: 240 }))
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch((err) => {
        if (!cancelled) setError(extractErrorMessage(err));
      });
    return () => {
      cancelled = true;
    };
  }, [orderId]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>입장권 QR</h3>
            <p className="modal-subtitle">{eventTitle}</p>
          </div>
          <button type="button" className="btn-secondary" onClick={onClose}>
            닫기
          </button>
        </div>
        {error ? (
          <p className="form-error">{error}</p>
        ) : dataUrl ? (
          <div className="ticket-qr">
            <img src={dataUrl} alt="입장권 QR 코드" width={240} height={240} />
            <p className="page-status">입구에서 이 QR을 보여주세요.</p>
          </div>
        ) : (
          <p className="page-status">불러오는 중...</p>
        )}
      </div>
    </div>
  );
}
