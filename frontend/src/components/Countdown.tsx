import { useEffect, useRef, useState } from "react";

function pad(n: number) {
  return String(n).padStart(2, "0");
}

// "04:32" under an hour, "2일 03:15:09" beyond that.
export function formatRemaining(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (days > 0) return `${days}일 ${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  if (hours > 0) return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  return `${pad(minutes)}:${pad(seconds)}`;
}

// 초당 한 번 도는 틱을 여기서 맡아서, 주변 페이지가 매초 다시 그려지지 않게 한다.
function useRemaining(target: string, onReached: () => void): number {
  const targetMs = new Date(target).getTime();
  const [remaining, setRemaining] = useState(() => targetMs - Date.now());
  const onReachedRef = useRef(onReached);
  onReachedRef.current = onReached;

  useEffect(() => {
    let fired = false;
    function tick() {
      const left = targetMs - Date.now();
      setRemaining(left);
      if (left <= 0 && !fired) {
        fired = true;
        onReachedRef.current();
      }
    }
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [targetMs]);

  return remaining;
}

interface HoldTimerProps {
  expireAt: string;
  onExpire: () => void;
}

/** 잡아 둔 좌석이 얼마나 더 예약 상태로 남는지. 마지막 1분에 긴급해지고 0이 되면 onExpire에 넘긴다. */
export function HoldTimer({ expireAt, onExpire }: HoldTimerProps) {
  const remaining = useRemaining(expireAt, onExpire);
  // 첫 값이 홀드 전체이므로, 5분을 하드코딩하지 않고도 막대가 100%에서 시작한다.
  const [total] = useState(() => Math.max(new Date(expireAt).getTime() - Date.now(), 1));
  const percent = Math.min(100, Math.max(0, (remaining / total) * 100));
  const urgent = remaining < 60_000;

  return (
    <div className={`hold-timer ${urgent ? "urgent" : ""}`} role="timer" aria-live="off">
      <div className="hold-timer-row">
        <span>결제 가능 시간</span>
        <strong>{formatRemaining(remaining)}</strong>
      </div>
      <div className="hold-timer-bar">
        <span style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

interface PassTimerProps {
  /** 대기열 입장권이 끝나는 로컬 시계 시각(ms). */
  deadline: number;
  onExpire: () => void;
}

/**
 * 대기열 입장권이 얼마나 남았는지. 입장권은 일부러 짧다(슬롯을 가진 사람 몫으로 잡아 두기 때문이다). 그래서 좌석을 고르는
 * 사람이 경고 없이 쫓겨나지 않고 시계를 볼 수 있어야 한다. 마지막 1분에 긴급해지고 0이 되면 onExpire에 넘긴다.
 */
export function PassTimer({ deadline, onExpire }: PassTimerProps) {
  const remaining = useRemaining(new Date(deadline).toISOString(), onExpire);
  const urgent = remaining < 60_000;

  return (
    <div className={`hold-timer ${urgent ? "urgent" : ""}`} role="timer" aria-live="off">
      <div className="hold-timer-row">
        <span>좌석을 잡을 수 있는 시간</span>
        <strong>{formatRemaining(remaining)}</strong>
      </div>
      {urgent && <small>시간이 끝나면 줄을 다시 서야 해요. 좌석을 잡으면 시간이 늘어나요.</small>}
    </div>
  );
}

/** "03:12 남음" as inline text, for tight spaces like a table cell. */
export function HoldRemaining({ expireAt, onExpire }: HoldTimerProps) {
  const remaining = useRemaining(expireAt, onExpire);
  return <small className={`hold-remaining ${remaining < 60_000 ? "urgent" : ""}`}>{formatRemaining(remaining)} 남음</small>;
}

interface OpenCountdownProps {
  openAt: string;
  onOpen: () => void;
}

/** 오픈 예정(UPCOMING) 공연의 예매 시작까지의 큰 카운트다운. */
export function OpenCountdown({ openAt, onOpen }: OpenCountdownProps) {
  const remaining = useRemaining(openAt, onOpen);
  return (
    <div className="open-countdown" role="timer" aria-live="off">
      <span>예매 오픈까지</span>
      <strong>{formatRemaining(remaining)}</strong>
    </div>
  );
}
