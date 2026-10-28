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

// The server sends a comment line every 15s, so three missed beats means the connection is dead even if the browser
// has not noticed (a phone that slept, a proxy that dropped it quietly).
const STREAM_IDLE_TIMEOUT_MS = 45_000;

/**
 * Listens to the queue over Server-Sent Events: the server pushes the new rank when it changes and the pass the moment
 * the person is let in, instead of the browser asking every couple of seconds. EventSource cannot send the login header,
 * so this reads the response stream itself.
 *
 * Resolves when the server ends the stream, rejects when it cannot be opened or goes quiet. Either way, if no pass came,
 * the caller should fall back to polling fetchQueueStatus.
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
