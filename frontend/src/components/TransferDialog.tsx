import { useState, type FormEvent } from "react";
import { createTransfer } from "../api/transfers";
import { extractErrorMessage } from "../utils/error";

interface TransferDialogProps {
  orderId: number;
  eventTitle: string;
  seatLabel: string;
  onDone: () => void;
  onCancel: () => void;
}

export function TransferDialog({ orderId, eventTitle, seatLabel, onDone, onCancel }: TransferDialogProps) {
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await createTransfer(orderId, email.trim());
      onDone();
    } catch (err) {
      setError(extractErrorMessage(err));
      setIsSubmitting(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <form className="modal-content modal-confirm transfer-dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <h3>티켓 양도</h3>
        <p className="modal-subtitle">
          {eventTitle}
          {"\n"}
          {seatLabel}
        </p>
        <input
          type="email"
          placeholder="받는 분의 가입 이메일"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          autoFocus
          maxLength={200}
        />
        <ul className="transfer-rules">
          <li>받는 분이 수락하면 티켓이 넘어가요. 거절하거나 취소하면 그대로 남아요.</li>
          <li>티켓은 한 번만 양도할 수 있고, 양도한 뒤에는 취소·환불할 수 없어요.</li>
          <li>공연 시작 2시간 전까지만 양도할 수 있어요.</li>
        </ul>
        {error && <p className="form-error">{error}</p>}
        <div className="modal-confirm-actions">
          <button type="button" className="btn-secondary" onClick={onCancel}>
            닫기
          </button>
          <button type="submit" disabled={isSubmitting || !email.trim()}>
            {isSubmitting ? "보내는 중..." : "양도 요청 보내기"}
          </button>
        </div>
      </form>
    </div>
  );
}
