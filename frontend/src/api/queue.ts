import { apiClient, getAccessToken } from "./client";
import type { QueueEnterResult, QueueStatusResult } from "../types";

export function enterQueue(eventId: number, captchaToken?: string) {
  return apiClient
    .post<QueueEnterResult>("/queue/enter", { eventId }, captchaToken ? { headers: { "X-Captcha-Token": captchaToken } } : undefined)
    .then((res) => res.data);
}

export function fetchQueueStatus(queueToken: string) {
  return apiClient.get<QueueStatusResult>("/queue/status", { params: { queueToken } }).then((res) => res.data);
}

// 서버는 15초마다 주석 줄을 보내므로 세 번 놓치면 브라우저가 알아채지 못해도(잠든 휴대폰, 조용히 연결을 끊은 프록시)
// 연결이 죽은 것이다.
const STREAM_IDLE_TIMEOUT_MS = 45_000;

/**
 * Server-Sent Events로 대기열을 듣는다. 브라우저가 2초마다 묻는 대신, 서버가 순번이 바뀌면 새 순번을, 사람이 입장하는
 * 순간 입장권을 밀어 준다. EventSource는 로그인 헤더를 보낼 수 없어서 응답 스트림을 직접 읽는다.
 *
 * 서버가 스트림을 끝내면 resolve하고, 열 수 없거나 조용해지면 reject한다. 어느 쪽이든 입장권이 오지 않았다면
 * 호출한 쪽은 fetchQueueStatus 폴링으로 되돌아가야 한다.
 */
export async function streamQueueStatus(
  queueToken: string,
  onStatus: (status: QueueStatusResult) => void,
  signal: AbortSignal,
): Promise<void> {
  const base = apiClient.defaults.baseURL ?? "";
  const token = getAccessToken();
  const idle = new AbortController();
  const abort = () => idle.abort();
  signal.addEventListener("abort", abort);
  let timer: number | undefined;
  const watchdog = () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(abort, STREAM_IDLE_TIMEOUT_MS);
  };

  try {
    watchdog();
    const response = await fetch(`${base}/queue/stream?queueToken=${encodeURIComponent(queueToken)}`, {
      headers: { Accept: "text/event-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      signal: idle.signal,
    });
    if (!response.ok || !response.body) {
      throw new Error(`queue stream refused (${response.status})`);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      watchdog();
      buffer += decoder.decode(value, { stream: true });
      let end = buffer.indexOf("\n\n");
      while (end >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const data = block
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trimStart())
          .join("\n");
        if (data) {
          onStatus(JSON.parse(data) as QueueStatusResult);
        }
        end = buffer.indexOf("\n\n");
      }
    }
  } finally {
    window.clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
