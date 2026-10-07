import { useEffect, useState } from "react";
import { acceptTransfer, cancelTransfer, declineTransfer, fetchTransfers } from "../api/transfers";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { formatDateTime } from "../utils/date";
import { extractErrorMessage } from "../utils/error";
import type { TransferItem, TransferStatus } from "../types";

const STATUS_LABEL: Record<TransferStatus, string> = {
  PENDING: "수락 대기",
  ACCEPTED: "양도 완료",
  DECLINED: "거절됨",
  CANCELLED: "취소됨",
  EXPIRED: "기간 만료",
};

type Pending = { kind: "accept" | "decline" | "cancel"; transfer: TransferItem };

const CONFIRM_COPY: Record<Pending["kind"], { title: string; confirm: string; message: (t: TransferItem) => string; danger: boolean }> = {
  accept: {
    title: "티켓 받기",
    confirm: "받기",
    message: (t) => `${t.eventTitle}\n${t.section} · ${t.seatNo}\n\n${t.counterpart}님이 보낸 티켓을 받을까요?`,
    danger: false,
  },
  decline: {
    title: "양도 거절",
    confirm: "거절하기",
    message: (t) => `${t.counterpart}님이 보낸 티켓을 거절할까요? 티켓은 보낸 분께 그대로 남아요.`,
    danger: true,
  },
  cancel: {
    title: "양도 취소",
    confirm: "양도 취소",
    message: (t) => `${t.counterpart}님께 보낸 양도 요청을 취소할까요?`,
    danger: true,
  },
};

export function TransfersPage() {
  useDocumentTitle("양도함");
  const { showToast } = useToast();

  const [transfers, setTransfers] = useState<TransferItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  function load() {
    return fetchTransfers()
      .then(setTransfers)
      .catch((err) => setError(extractErrorMessage(err)));
  }

  useEffect(() => {
    load().finally(() => setIsLoading(false));
  }, []);

  async function handleConfirm() {
    if (!pending) return;
    const { kind, transfer } = pending;
    setPending(null);
    setBusyId(transfer.transferId);
    try {
      if (kind === "accept") await acceptTransfer(transfer.transferId);
      else if (kind === "decline") await declineTransfer(transfer.transferId);
      else await cancelTransfer(transfer.transferId);
      showToast(kind === "accept" ? "티켓을 받았어요. 공연 2시간 전부터 내 티켓함에서 QR을 확인할 수 있어요." : "처리했어요.");
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setBusyId(null);
      // Reload either way: a refused answer usually means the transfer already changed state.
      await load();
    }
  }

  if (isLoading) {
    return (
      <div>
        <h1>양도함</h1>
        <TextRowsSkeleton rows={4} />
      </div>
    );
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  const incoming = transfers.filter((t) => t.direction === "INCOMING");
  const outgoing = transfers.filter((t) => t.direction === "OUTGOING");

  function renderList(items: TransferItem[], empty: string) {
    if (items.length === 0) return <p className="page-status">{empty}</p>;
    return (
      <ul className="transfer-list">
        {items.map((t) => (
          <li key={t.transferId} className="transfer-card">
            <div className="transfer-card-main">
              <strong>{t.eventTitle}</strong>
              <span>
                {formatDateTime(t.eventStartAt)} · {t.venue}
              </span>
              <span>
                {t.grade}석 · {t.section} · {t.seatNo}
              </span>
              <span className="transfer-card-who">
                {t.direction === "INCOMING" ? `보낸 사람 ${t.counterpart}` : `받는 사람 ${t.counterpart}`}
              </span>
            </div>
            <div className="transfer-card-side">
              <span className={`transfer-status transfer-status-${t.status.toLowerCase()}`}>{STATUS_LABEL[t.status]}</span>
              {t.status === "PENDING" && t.direction === "INCOMING" && (
                <div className="transfer-actions">
                  <button type="button" disabled={busyId === t.transferId} onClick={() => setPending({ kind: "accept", transfer: t })}>
                    받기
                  </button>
                  <button type="button" className="btn-secondary" disabled={busyId === t.transferId} onClick={() => setPending({ kind: "decline", transfer: t })}>
                    거절
                  </button>
                </div>
              )}
              {t.status === "PENDING" && t.direction === "OUTGOING" && (
                <button type="button" className="btn-secondary" disabled={busyId === t.transferId} onClick={() => setPending({ kind: "cancel", transfer: t })}>
                  양도 취소
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div>
      <h1>양도함</h1>
      <p className="form-notice">
        티켓은 공연 시작 2시간 전까지, 한 번만 양도할 수 있어요. 양도는 내 주문에서 시작하고, 받은 티켓은 수락하면 내 티켓함으로 들어와요.
      </p>

      <h2 className="detail-section-title">받은 양도</h2>
      {renderList(incoming, "받은 양도 요청이 없어요.")}

      <h2 className="detail-section-title">보낸 양도</h2>
      {renderList(outgoing, "보낸 양도 요청이 없어요.")}

      {pending && (
        <ConfirmDialog
          title={CONFIRM_COPY[pending.kind].title}
          message={CONFIRM_COPY[pending.kind].message(pending.transfer)}
          confirmLabel={CONFIRM_COPY[pending.kind].confirm}
          danger={CONFIRM_COPY[pending.kind].danger}
          onConfirm={handleConfirm}
          onCancel={() => setPending(null)}
        />
      )}
    </div>
  );
}
