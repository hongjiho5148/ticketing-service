import { useEffect, useState } from "react";
import { fetchAlertStatus, subscribeAlert, unsubscribeAlert, type AlertKind } from "../api/alerts";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";
import { extractErrorMessage } from "../utils/error";

interface AlertSubscribeCardProps {
  eventId: number;
  kind: AlertKind;
}

const COPY: Record<AlertKind, { title: string; description: string; on: string; off: string; done: string }> = {
  "open-alert": {
    title: "오픈 알림",
    description: "예매가 시작되면 이메일로 알려드려요.",
    on: "🔔 오픈 알림 받는 중 (해제)",
    off: "🔔 오픈 알림 신청",
    done: "예매가 열리면 이메일로 알려드릴게요.",
  },
  waitlist: {
    title: "취소표 알림",
    description: "취소되거나 결제되지 않은 좌석이 풀리면 이메일로 알려드려요. 먼저 잡는 분이 임자예요.",
    on: "🔔 취소표 알림 받는 중 (해제)",
    off: "🔔 취소표 알림 신청",
    done: "취소표가 나오면 이메일로 알려드릴게요.",
  },
};

export function AlertSubscribeCard({ eventId, kind }: AlertSubscribeCardProps) {
  const { user } = useAuth();
  const { showToast } = useToast();
  const copy = COPY[kind];

  const [subscribed, setSubscribed] = useState(false);
  const [isBusy, setIsBusy] = useState(false);

  useEffect(() => {
    fetchAlertStatus(eventId, kind)
      .then((status) => setSubscribed(status.subscribed))
      .catch(() => undefined);
  }, [eventId, kind]);

  async function toggle() {
    setIsBusy(true);
    try {
      if (subscribed) {
        await unsubscribeAlert(eventId, kind);
        setSubscribed(false);
        showToast("알림을 해제했어요.");
      } else {
        await subscribeAlert(eventId, kind);
        setSubscribed(true);
        showToast(copy.done);
      }
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setIsBusy(false);
    }
  }

  // Kakao logins can come without an email; those accounts have nowhere to send the alert to.
  const hasEmail = Boolean(user?.email);

  return (
    <div className="alert-card">
      <h3>{copy.title}</h3>
      <p>{copy.description}</p>
      <button type="button" className={subscribed ? "btn-secondary" : ""} onClick={toggle} disabled={isBusy || !hasEmail}>
        {subscribed ? copy.on : copy.off}
      </button>
      <small>
        {hasEmail
          ? "마이페이지에서 이메일 알림을 꺼두면 발송되지 않아요."
          : "이메일이 등록되지 않은 계정은 알림을 받을 수 없어요."}
      </small>
    </div>
  );
}
