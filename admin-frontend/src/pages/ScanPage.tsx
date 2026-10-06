import { useState, type FormEvent } from "react";
import { scanTicket } from "../api/admin";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import type { ScanResult } from "../types";
import { extractErrorMessage } from "../utils/error";

interface ScanLogEntry {
  result: ScanResult;
  scannedAt: string;
}

export function ScanPage() {
  useDocumentTitle("입장 스캔");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [log, setLog] = useState<ScanLogEntry[]>([]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!token.trim()) {
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      const result = await scanTicket(token.trim());
      setLog((prev) => [{ result, scannedAt: new Date().toISOString() }, ...prev]);
      setToken("");
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className="form-page">
      <h1>입장 스캔</h1>
      <p className="form-notice">QR 코드에 담긴 토큰 문자열을 붙여넣고 입장 처리하세요.</p>
      <form onSubmit={handleSubmit} className="form">
        <label>
          QR 토큰
          <textarea
            value={token}
            onChange={(e) => setToken(e.target.value)}
            rows={3}
            placeholder="스캔한 QR의 토큰 값을 붙여넣으세요"
            required
            autoFocus
          />
        </label>
        {error && <p className="form-error">{error}</p>}
        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "처리 중..." : "입장 처리"}
        </button>
      </form>

      {log.length > 0 && (
        <ul className="scan-log">
          {log.map((entry) => (
            <li key={`${entry.result.ticketId}-${entry.scannedAt}`} className="scan-result success">
              <strong>입장 완료</strong>
              <span>주문 #{entry.result.orderId} · 좌석 #{entry.result.seatId}</span>
              <span>{new Date(entry.result.usedAt).toLocaleTimeString("ko-KR")}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
