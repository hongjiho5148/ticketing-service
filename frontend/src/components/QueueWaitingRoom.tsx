import { useEffect, useRef, useState } from "react";
import { isAxiosError } from "axios";
import { enterQueue, fetchQueueStatus, streamQueueStatus } from "../api/queue";
import type { ApiErrorBody, QueueStatusResult } from "../types";
import { extractErrorMessage } from "../utils/error";
import { isCaptchaEnabled } from "../utils/recaptcha";
import { ReCaptcha } from "./ReCaptcha";

interface QueueWaitingRoomProps {
  eventId: number;
  /** expiresInSeconds: how long the pass has left, when the server said. */
  onPassed: (passToken: string, expiresInSeconds: number | null) => void;
}

const POLL_INTERVAL_MS = 2000;

export function QueueWaitingRoom({ eventId, onPassed }: QueueWaitingRoomProps) {
  const [rankNo, setRankNo] = useState<number | null>(null);
  const [estimatedWaitSeconds, setEstimatedWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The solved captcha that gets us into the queue. Kept once solved: if the widget later expires,
  // that must not tear down a queue entry that is already waiting.
  const [enterToken, setEnterToken] = useState<string | null>(null);
  const [captchaKey, setCaptchaKey] = useState(0);
  // The server takes people out of the line when it has not heard from them for a while (a closed laptop, a phone that
  // lost signal). Coming back, they find their place gone and can join again at the back.
  const [removedFromLine, setRemovedFromLine] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const onPassedRef = useRef(onPassed);
  onPassedRef.current = onPassed;

  useEffect(() => {
    if (isCaptchaEnabled && !enterToken) return;
    let cancelled = false;
    let intervalId: number | undefined;
    const controller = new AbortController();

    // Returns true once the person has been let in.
    function apply(status: QueueStatusResult): boolean {
      if (cancelled) return true;
      if (status.status === "PASSED" && status.passToken) {
        if (intervalId) window.clearInterval(intervalId);
        controller.abort();
        onPassedRef.current(status.passToken, status.passExpiresInSeconds);
        return true;
      }
      setRankNo(status.rankNo);
      setEstimatedWaitSeconds(status.estimatedWaitSeconds);
      return false;
    }

    async function poll(queueToken: string) {
      try {
        apply(await fetchQueueStatus(queueToken));
      } catch (err) {
        if (cancelled) return;
        if (isAxiosError<ApiErrorBody>(err) && err.response?.data?.code === "QUEUE_TOKEN_NOT_FOUND") {
          if (intervalId) window.clearInterval(intervalId);
          setRemovedFromLine(true);
          return;
        }
        setError(extractErrorMessage(err));
      }
    }

    // The server pushes updates over one open connection. If that cannot be had (the server is full, something between
    // us and it drops the stream) the page asks every two seconds instead, which is how it worked before.
    async function watch(queueToken: string) {
      let passed = false;
      try {
        await streamQueueStatus(queueToken, (status) => {
          if (apply(status)) passed = true;
        }, controller.signal);
      } catch {
        // fall through to polling
      }
      if (cancelled || passed) return;
      intervalId = window.setInterval(() => poll(queueToken), POLL_INTERVAL_MS);
    }

    // A browser slows or pauses a tab that is out of sight, and a phone that slept may have lost the connection without
    // noticing. When the tab is shown again, ask at once instead of waiting for the next tick or the next push.
    let queueToken: string | null = null;
    function onVisible() {
      if (document.visibilityState === "visible" && queueToken) void poll(queueToken);
    }
    document.addEventListener("visibilitychange", onVisible);

    enterQueue(eventId, enterToken ?? undefined)
      .then((entered) => {
        if (cancelled) return;
        queueToken = entered.queueToken;
        setRankNo(entered.rankNo);
        setEstimatedWaitSeconds(entered.estimatedWaitSeconds);
        void watch(entered.queueToken);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(extractErrorMessage(err));
        if (isCaptchaEnabled) {
          // The token is spent whatever the outcome, so ask for a fresh one.
          setEnterToken(null);
          setCaptchaKey((k) => k + 1);
        }
      });

    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisible);
      controller.abort();
      if (intervalId) window.clearInterval(intervalId);
    };
  }, [eventId, enterToken, attempt]);

  if (isCaptchaEnabled && !enterToken) {
    return (
      <div className="queue-room">
        <div className="queue-card">
          <h2>대기열 입장 전 확인</h2>
          <p className="page-status">공정한 예매를 위해 로봇이 아님을 확인해주세요.</p>
          <ReCaptcha
            key={captchaKey}
            onChange={(token) => {
              if (!token) return;
              setError(null);
              setEnterToken(token);
            }}
          />
          {error && <p className="form-error">{error}</p>}
        </div>
      </div>
    );
  }

  function joinAgain() {
    setRemovedFromLine(false);
    setError(null);
    setRankNo(null);
    if (isCaptchaEnabled) {
      // The earlier token was spent, so the check has to be done again.
      setEnterToken(null);
      setCaptchaKey((k) => k + 1);
    } else {
      setAttempt((n) => n + 1);
    }
  }

  if (removedFromLine) {
    return (
      <div className="queue-room">
        <div className="queue-card">
          <h2>줄에서 빠졌어요</h2>
          <p className="page-status">한동안 연결이 없어서 대기열에서 자리가 정리됐어요. 다시 줄을 서면 맨 뒤에서 시작해요.</p>
          <button type="button" onClick={joinAgain}>
            다시 줄 서기
          </button>
        </div>
      </div>
    );
  }

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
