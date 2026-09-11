import { useEffect, useRef, useState } from "react";
import { enterQueue, fetchQueueStatus } from "../api/queue";
import { extractErrorMessage } from "../utils/error";

interface QueueWaitingRoomProps {
  eventId: number;
  onPassed: (passToken: string) => void;
}

const POLL_INTERVAL_MS = 2000;

export function QueueWaitingRoom({ eventId, onPassed }: QueueWaitingRoomProps) {
  const [rankNo, setRankNo] = useState<number | null>(null);
  const [estimatedWaitSeconds, setEstimatedWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const onPassedRef = useRef(onPassed);
  onPassedRef.current = onPassed;

  useEffect(() => {
    let cancelled = false;
    let intervalId: number | undefined;

    async function poll(queueToken: string) {
      try {
        const status = await fetchQueueStatus(queueToken);
        if (cancelled) return;
        if (status.status === "PASSED" && status.passToken) {
          if (intervalId) window.clearInterval(intervalId);
          onPassedRef.current(status.passToken);
          return;
        }
        setRankNo(status.rankNo);
        setEstimatedWaitSeconds(status.estimatedWaitSeconds);
      } catch (err) {
        if (!cancelled) setError(extractErrorMessage(err));
      }
    }

    enterQueue(eventId)
      .then((entered) => {
        if (cancelled) return;
        setRankNo(entered.rankNo);
        setEstimatedWaitSeconds(entered.estimatedWaitSeconds);
        intervalId = window.setInterval(() => poll(entered.queueToken), POLL_INTERVAL_MS);
      })
      .catch((err) => {
        if (!cancelled) setError(extractErrorMessage(err));
      });

    return () => {
      cancelled = true;
      if (intervalId) window.clearInterval(intervalId);
    };
  }, [eventId]);

  return (
    <div className="queue-room">
      <div className="queue-card">
        <div className="queue-spinner" />
        <h2>대기열에 입장했습니다</h2>
        {error ? (
          <p className="form-error">{error}</p>
        ) : rankNo === null ? (
          <p className="page-status">대기열 확인 중...</p>
        ) : (
          <>
            <p className="queue-rank">
              내 순번 <strong>{rankNo}번째</strong>
            </p>
            <p className="queue-wait">
              예상 대기시간 약 {estimatedWaitSeconds ?? 0}초
            </p>
            <p className="queue-notice">순서가 되면 자동으로 예매 화면으로 이동합니다. 페이지를 벗어나지 마세요.</p>
          </>
        )}
      </div>
    </div>
  );
}
