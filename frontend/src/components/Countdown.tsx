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

interface PassTimerProps {
  /** Local clock time (ms) at which the queue pass runs out. */
  deadline: number;
  onExpire: () => void;
}

/**
 * How long the queue pass has left. The pass is short on purpose (a slot is held for whoever has it), so the person
 * choosing seats should see the clock instead of being turned away without warning. Goes urgent in the last minute and
 * hands over to onExpire at zero.
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
