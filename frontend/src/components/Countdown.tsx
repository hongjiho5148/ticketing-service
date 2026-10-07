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

// Owns the once-a-second tick so the page around it doesn't re-render every second.
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

/** How long the held seat stays reserved. Turns urgent in the last minute and hands over to onExpire at zero. */
export function HoldTimer({ expireAt, onExpire }: HoldTimerProps) {
  const remaining = useRemaining(expireAt, onExpire);
  // The first reading is the whole hold, which gives the bar its 100% without hard-coding the 5 minutes.
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

interface OpenCountdownProps {
  openAt: string;
  onOpen: () => void;
}

/** Big countdown to a UPCOMING event's booking start. */
export function OpenCountdown({ openAt, onOpen }: OpenCountdownProps) {
  const remaining = useRemaining(openAt, onOpen);
  return (
    <div className="open-countdown" role="timer" aria-live="off">
      <span>예매 오픈까지</span>
      <strong>{formatRemaining(remaining)}</strong>
    </div>
  );
}
