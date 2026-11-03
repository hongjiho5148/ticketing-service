import { useEffect, useRef, useState } from "react";
import { isAxiosError } from "axios";
import { enterQueue, fetchQueueStatus, streamQueueStatus } from "../api/queue";
import type { ApiErrorBody, QueueStatusResult } from "../types";
import { extractErrorMessage } from "../utils/error";
import { isCaptchaEnabled } from "../utils/recaptcha";
import { ReCaptcha } from "./ReCaptcha";

interface QueueWaitingRoomProps {
  eventId: number;
  /** expiresInSeconds: 입장권이 얼마나 남았는지(서버가 알려 줬을 때). */
  onPassed: (passToken: string, expiresInSeconds: number | null) => void;
  /** 사람이 대기열로 돌아온 이유(잃어버린 홀드, 끝난 입장권)다. 다시 입장할 때까지 보여 준다. */
  notice?: string | null;
}

const POLL_INTERVAL_MS = 2000;

export function QueueWaitingRoom({ eventId, onPassed, notice }: QueueWaitingRoomProps) {
  const [rankNo, setRankNo] = useState<number | null>(null);
  const [estimatedWaitSeconds, setEstimatedWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 대기열에 들어가게 해 주는 푼 캡차다. 한 번 풀면 계속 보관한다. 위젯이 나중에 만료되어도
  // 이미 기다리고 있는 대기열 항목을 무너뜨리면 안 된다.
  const [enterToken, setEnterToken] = useState<string | null>(null);
  const [captchaKey, setCaptchaKey] = useState(0);
  // 서버는 한동안 소식이 없는 사람(덮은 노트북, 신호를 잃은 휴대폰)을 줄에서 뺀다. 돌아오면 자리가 사라진 것을 보고
  // 맨 뒤에 다시 설 수 있다.
  const [removedFromLine, setRemovedFromLine] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const onPassedRef = useRef(onPassed);
  onPassedRef.current = onPassed;

  useEffect(() => {
    if (isCaptchaEnabled && !enterToken) return;
    let cancelled = false;
    let intervalId: number | undefined;
    const controller = new AbortController();

    // 사람이 입장했으면 true를 돌려준다.
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

    // 서버가 열려 있는 연결 하나로 업데이트를 밀어 준다. 그것을 얻을 수 없으면(서버가 가득 찼거나 사이의 무언가가
    // 스트림을 끊으면) 예전처럼 페이지가 2초마다 묻는다.
    async function watch(queueToken: string) {
      let passed = false;
      try {
        await streamQueueStatus(queueToken, (status) => {
          if (apply(status)) passed = true;
        }, controller.signal);
      } catch {
        // 폴링으로 넘어간다
      }
      if (cancelled || passed) return;
      intervalId = window.setInterval(() => poll(queueToken), POLL_INTERVAL_MS);
    }

    // 브라우저는 보이지 않는 탭을 느리게 하거나 멈추고, 잠들었던 휴대폰은 알아채지 못한 채 연결을 잃었을 수 있다.
    // 탭이 다시 보이면 다음 틱이나 다음 푸시를 기다리지 않고 곧바로 묻는다.
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
          // 결과와 상관없이 토큰은 쓰였으므로 새 것을 요청한다.
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
          {notice && <p className="form-error">{notice}</p>}
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
      // 이전 토큰은 쓰였으므로 확인을 다시 해야 한다.
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
        {notice && <p className="form-error">{notice}</p>}
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
