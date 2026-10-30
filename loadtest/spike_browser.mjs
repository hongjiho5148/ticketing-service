// spike.js와 같은 오픈 순간 폭주(N명이 동시에 줄 서기 -> 입장 -> 좌석 하나 예약 -> 취소)를 "실제 브라우저처럼" 한다.
// k6는 SSE 스트림을 읽지 못해서 대기자가 2초마다 상태를 묻는 경로(폴링)로만 돌렸는데, 이 스크립트는 브라우저가 쓰는 두 경로를
// 같은 도구로 비교한다: --mode=sse(서버가 알려주는 실시간 연결, 못 열면 폴링으로 대체) / --mode=poll(2초마다 묻기).
//
//   준비: seed_spike_users.sql로 spike1~spikeN 계정을 만들어 둔다. 레이트 리미팅·캡차는 끄고, nginx의 IP당 연결 제한
//         (limit_conn queue_stream_per_ip)은 N보다 크게 올린다(한 컴퓨터에서 모두 여니까).
//   실행: docker run --rm --network ticketing-service_default -v "$(pwd)/loadtest:/lt:ro" node:22-alpine \
//           node /lt/spike_browser.mjs --mode=sse --users=1000 --event=1 --base=http://nginx/api
import process from "node:process";

const args = Object.fromEntries(process.argv.slice(2).map((a) => a.replace(/^--/, "").split("=")));
const MODE = args.mode ?? "sse";
const USERS = Number(args.users ?? 1000);
const EVENT_ID = Number(args.event ?? 1);
const BASE = args.base ?? "http://localhost:8080/api";
const PREFIX = args.prefix ?? "spike";
const POLL_MS = Number(args.poll ?? 2000);
const MAX_WAIT_MS = Number(args.maxwait ?? 300) * 1000;
const CANCEL = args.cancel !== "false";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const counts = { enter: 0, status: 0, streamOpen: 0, seatList: 0, reserve: 0, cancel: 0, streamFallback: 0, enterFailed: 0, gaveUp: 0, reserved: 0, seatTakenGaveUp: 0, reserveError: 0 };
const errors = {};
const enterMs = [];
const waitS = [];
const reserveMs = [];
const journeyS = [];

function note(kind) {
  errors[kind] = (errors[kind] ?? 0) + 1;
}

async function http(method, path, token, body, extra = {}) {
  const started = Date.now();
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {}
    return { status: res.status, json, ms: Date.now() - started };
  } catch (e) {
    return { status: 0, json: null, ms: Date.now() - started, error: String(e.cause?.code ?? e.message).slice(0, 40) };
  }
}

async function login(n) {
  const r = await http("POST", "/auth/login", null, { email: `${PREFIX}${n}@example.com`, password: "password123" });
  return r.status === 200 ? r.json.accessToken : null;
}

// 입장할 때까지 기다리고 passToken을 돌려준다(못 들어오면 null)
async function waitForPass(token, queueToken, deadline) {
  if (MODE === "sse") {
    counts.streamOpen++;
    try {
      const res = await fetch(`${BASE}/queue/stream?queueToken=${queueToken}`, {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
        signal: AbortSignal.timeout(Math.max(1000, deadline - Date.now())),
      });
      if (!res.ok || !res.body) throw new Error(`stream ${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let cut;
        while ((cut = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, cut);
          buffer = buffer.slice(cut + 2);
          const data = block.split("\n").find((l) => l.startsWith("data:"));
          if (!data) continue;
          const s = JSON.parse(data.slice(5));
          if (s.status === "PASSED") {
            await reader.cancel();
            return s.passToken;
          }
        }
      }
    } catch {
      // 연결이 안 열리거나 끊기면 브라우저처럼 폴링으로 이어서 기다린다
    }
    counts.streamFallback++;
  }
  while (Date.now() < deadline) {
    counts.status++;
    const r = await http("GET", `/queue/status?queueToken=${queueToken}`, token);
    if (r.status === 200 && r.json.status === "PASSED") return r.json.passToken;
    if (r.status !== 200) note(`status ${r.status}${r.error ? " " + r.error : ""}`);
    await sleep(POLL_MS * (0.8 + Math.random() * 0.4));
  }
  return null;
}

async function journey(token) {
  const t0 = Date.now();
  counts.enter++;
  const entered = await http("POST", "/queue/enter", token, { eventId: EVENT_ID });
  enterMs.push(entered.ms);
  if (entered.status !== 200) {
    counts.enterFailed++;
    note(`enter ${entered.status}${entered.error ? " " + entered.error : ""}`);
    return;
  }
  const queueToken = entered.json.queueToken;

  const passToken = await waitForPass(token, queueToken, t0 + MAX_WAIT_MS);
  if (!passToken) {
    counts.gaveUp++;
    return;
  }
  waitS.push((Date.now() - t0) / 1000);

  let reservationId = null;
  for (let attempt = 0; attempt < 3 && reservationId === null; attempt++) {
    counts.seatList++;
    const list = await http("GET", `/events/${EVENT_ID}/seats`, token);
    const seats = Array.isArray(list.json) ? list.json : list.json?.seats ?? [];
    const available = seats.filter((s) => s.status === "AVAILABLE");
    if (available.length === 0) {
      counts.seatTakenGaveUp++;
      break;
    }
    const seat = available[Math.floor(Math.random() * available.length)];
    counts.reserve++;
    const res = await http("POST", "/reservations", token, { seatId: seat.id }, { "X-Pass-Token": passToken });
    reserveMs.push(res.ms);
    if (res.status === 201) {
      reservationId = res.json.reservationId;
      counts.reserved++;
    } else if (res.status !== 409) {
      counts.reserveError++;
      note(`reserve ${res.status}${res.error ? " " + res.error : ""}`);
      break;
    } else if (attempt === 2) {
      counts.seatTakenGaveUp++;
    }
  }
  journeyS.push((Date.now() - t0) / 1000);
  if (CANCEL && reservationId !== null) {
    counts.cancel++;
    await http("DELETE", `/reservations/${reservationId}`, token);
  }
}

const pct = (arr, p) => {
  if (!arr.length) return null;
  const a = [...arr].sort((x, y) => x - y);
  return a[Math.min(a.length - 1, Math.floor(a.length * p))];
};
const avg = (arr) => (arr.length ? arr.reduce((s, v) => s + v, 0) / arr.length : null);

async function main() {
  // 오픈 전에 미리 로그인(측정 밖)
  const tokens = [];
  for (let from = 1; from <= USERS; from += 50) {
    const batch = await Promise.all(Array.from({ length: Math.min(50, USERS - from + 1) }, (_, k) => login(from + k)));
    tokens.push(...batch);
  }
  const missing = tokens.filter((t) => t === null).length;
  if (missing) throw new Error(`${missing} accounts could not log in - run seed_spike_users.sql first`);

  const started = Date.now();
  const startedAtSec = Math.floor(started / 1000);
  await Promise.all(tokens.map((t) => journey(t)));
  const elapsed = (Date.now() - started) / 1000;

  const httpRequests = counts.enter + counts.status + counts.streamOpen + counts.seatList + counts.reserve + counts.cancel;
  console.log(
    JSON.stringify(
      {
        mode: MODE,
        users: USERS,
        startedAtSec,
        elapsedSec: Number(elapsed.toFixed(1)),
        httpRequests,
        counts,
        errors,
        enterMs: { avg: Math.round(avg(enterMs)), p95: pct(enterMs, 0.95), max: Math.max(...enterMs) },
        waitS: { avg: Number(avg(waitS)?.toFixed(1)), p50: pct(waitS, 0.5), p95: pct(waitS, 0.95), max: Math.max(...waitS) },
        reserveMs: { avg: Math.round(avg(reserveMs)), p95: pct(reserveMs, 0.95), max: Math.max(...reserveMs) },
        journeyS: { avg: Number(avg(journeyS)?.toFixed(1)), p95: pct(journeyS, 0.95) },
      },
      null,
      2,
    ),
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
