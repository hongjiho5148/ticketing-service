// 대기열에서 기다리는 사람들이 "들어가도 되나요?"를 어떻게 알게 되는지 비교합니다: 2초마다 묻기(poll) vs 서버가 알려주기(sse).
//
//   node loadtest/queue_wait.mjs --mode=poll --waiters=300 --event=4 --ttl=20000
//   node loadtest/queue_wait.mjs --mode=sse  --waiters=300 --event=4 --ttl=20000
//   node loadtest/queue_wait.mjs --mode=sse  ... --killpubsub=3000   (3초마다 Redis 구독 연결을 끊어 입장 알림을 일부러 잃게 함)
//
// 줄이 천천히 흐르도록 backend를 QUEUE_ACTIVE_CAPACITY=50, QUEUE_PASS_TOKEN_TTL_MS=20000 정도로 띄워 둡니다(입장권이 20초 뒤
// 만료되면 다음 50명이 들어갑니다). 로그인 캡차와 레이트 리미팅은 꺼 둡니다. --ttl은 backend의 값과 같아야 합니다.
//
// 재는 것: 클라이언트가 보낸 HTTP 요청 수, 입장 허용부터 클라이언트가 알게 되기까지의 지연, 그동안 게이트웨이·nginx·backend의 CPU.
// 입장 허용 시각은 Redis의 입장 ZSET 점수(만료 시각)에서 --ttl을 빼서 구하고, Redis와 이 PC의 시계 차이는 실행 전에 맞춥니다.
import { execFile, execFileSync } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const args = Object.fromEntries(process.argv.slice(2).map((a) => a.replace(/^--/, "").split("=")));
const MODE = args.mode ?? "poll";
const WAITERS = Number(args.waiters ?? 300);
const EVENT_ID = Number(args.event ?? 4);
const TTL_MS = Number(args.ttl ?? 20000);
const ACCOUNTS = Number(args.accounts ?? 20);
const POLL_MS = Number(args.poll ?? 2000);
const BASE = args.base ?? "http://localhost:8080/api";
const MAX_RUN_MS = Number(args.max ?? 240000);
const REDIS = args.redis ?? "ticketing-redis";
const KILL_PUBSUB_MS = Number(args.killpubsub ?? 0);
const WATCH = (args.watch ?? "ticketing-gateway,ticketing-nginx,ticketing-service-backend").split(",");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const redis = (...a) => execFileSync("docker", ["exec", REDIS, "redis-cli", "--raw", ...a], { encoding: "utf8" });
// 측정 중에는 이벤트 루프를 막지 않도록 비동기로 부른다(막으면 클라이언트 전체가 멈춰 지연이 왜곡된다).
const redisAsync = async (...a) => (await execFileAsync("docker", ["exec", REDIS, "redis-cli", "--raw", ...a], { encoding: "utf8" })).stdout;

async function json(method, path, token, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}`);
  return res.json();
}

// Redis 시계 - 이 PC 시계. 왕복이 가장 짧았던 측정을 쓴다.
function clockOffsetMs() {
  let best = null;
  for (let i = 0; i < 15; i++) {
    const t0 = Date.now();
    const [sec, micro] = redis("TIME").trim().split("\n").map(Number);
    const t1 = Date.now();
    const offset = sec * 1000 + micro / 1000 - (t0 + t1) / 2;
    if (!best || t1 - t0 < best.rtt) best = { rtt: t1 - t0, offset };
  }
  return best;
}

const stats = { statusRequests: 0, streamOpens: 0, streamFallbacks: 0, rankPushes: 0, statusErrors: 0 };
const passedAt = new Map(); // queueToken -> 이 PC 기준 ms
const admitScore = new Map(); // queueToken -> 입장 ZSET 점수(만료 시각, Redis 시계)

async function poller(w, stop) {
  while (!stop.done && !passedAt.has(w.queueToken)) {
    try {
      stats.statusRequests++;
      const s = await json("GET", `/queue/status?queueToken=${w.queueToken}`, w.jwt);
      if (s.status === "PASSED") {
        passedAt.set(w.queueToken, Date.now());
        return;
      }
    } catch {
      stats.statusErrors++;
    }
    await sleep(POLL_MS);
  }
}

async function streamer(w, stop) {
  while (!stop.done && !passedAt.has(w.queueToken)) {
    try {
      stats.streamOpens++;
      const res = await fetch(`${BASE}/queue/stream?queueToken=${w.queueToken}`, {
        headers: { Authorization: `Bearer ${w.jwt}`, Accept: "text/event-stream" },
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
            passedAt.set(w.queueToken, Date.now());
            await reader.cancel();
            return;
          }
          stats.rankPushes++;
        }
      }
    } catch (e) {
      stats.streamFallbacks++;
      const why = String(e.cause?.code ?? e.message).slice(0, 60);
      (stats.fallbackReasons ??= {})[why] = (stats.fallbackReasons[why] ?? 0) + 1;
    }
    if (!stop.done && !passedAt.has(w.queueToken)) await sleep(1000); // 끊기면 잠깐 뒤 다시 연결
  }
}

async function main() {
  const clock = clockOffsetMs();
  console.log(`clock offset (redis - local): ${clock.offset.toFixed(1)} ms (rtt ${clock.rtt} ms)`);

  const jwts = [];
  for (let i = 1; i <= ACCOUNTS; i++) {
    const r = await json("POST", "/auth/login", null, { email: `loadtest${i}@example.com`, password: "password123" });
    jwts.push(r.accessToken);
  }

  const waiters = [];
  for (let i = 0; i < WAITERS; i += 50) {
    const batch = await Promise.all(
      Array.from({ length: Math.min(50, WAITERS - i) }, async (_, k) => {
        const jwt = jwts[(i + k) % jwts.length];
        const e = await json("POST", "/queue/enter", jwt, { eventId: EVENT_ID });
        return { jwt, queueToken: e.queueToken, entered: Date.now() };
      }),
    );
    waiters.push(...batch);
  }
  console.log(`${waiters.length} waiters in the queue; mode=${MODE}`);

  const stop = { done: false };
  const t0 = Date.now();
  const tasks = waiters.map((w) => (MODE === "sse" ? streamer(w, stop) : poller(w, stop)));

  // 입장 ZSET을 수시로 읽어 입장 허용 시각(점수 - ttl)을 모으고, 컨테이너 CPU를 잰다.
  const cpu = Object.fromEntries(WATCH.map((n) => [n, []]));
  const admitSampler = (async () => {
    while (!stop.done) {
      try {
        const out = (await redisAsync("ZRANGE", `queue:active:${EVENT_ID}`, "0", "-1", "WITHSCORES")).trim().split("\n");
        for (let i = 0; i + 1 < out.length; i += 2) {
          // Redisson 코덱이 멤버 앞에 제어 문자를 붙여 저장한다 - 토큰(UUID)만 꺼낸다
          const token = out[i].match(/[0-9a-f]{8}-[0-9a-f-]{27}/)?.[0];
          if (token && !admitScore.has(token)) admitScore.set(token, Number(out[i + 1]));
        }
      } catch {}
      await sleep(500);
    }
  })();
  const cpuSampler = (async () => {
    while (!stop.done) {
      try {
        const { stdout } = await execFileAsync("docker", ["stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}"], { encoding: "utf8" });
        const sums = Object.fromEntries(WATCH.map((w) => [w, 0]));
        for (const row of stdout.trim().split("\n")) {
          const [name, pct] = row.split(" ");
          for (const w of WATCH) if (name.startsWith(w)) sums[w] += parseFloat(pct);
        }
        for (const w of WATCH) cpu[w].push(sums[w]); // 레플리카가 여럿이면 합계
      } catch {}
      await sleep(3000);
    }
  })();

  // 구독 연결을 끊으면 Redisson이 다시 구독하지만 그 사이에 발행된 입장 알림은 잃는다.
  let kills = 0;
  const killer = (async () => {
    while (KILL_PUBSUB_MS > 0 && !stop.done) {
      try {
        const out = (await redisAsync("CLIENT", "KILL", "TYPE", "pubsub")).trim();
        kills += Number(out) || 0;
      } catch {}
      await sleep(KILL_PUBSUB_MS);
    }
  })();

  const deadline = t0 + MAX_RUN_MS;
  while (passedAt.size < waiters.length && Date.now() < deadline) await sleep(500);
  stop.done = true;
  await Promise.allSettled(tasks);
  await Promise.allSettled([admitSampler, cpuSampler, killer]);
  const elapsed = (Date.now() - t0) / 1000;

  // 지연 = 클라이언트가 안 시각 - 입장 허용 시각. 보기 시작하기 전에 이미 들어간 사람은 뺀다.
  const lags = [];
  for (const w of waiters) {
    const p = passedAt.get(w.queueToken);
    const score = admitScore.get(w.queueToken);
    if (p === undefined || score === undefined) continue;
    const admittedLocal = score - TTL_MS - clock.offset;
    if (admittedLocal < t0) continue;
    lags.push(p - admittedLocal);
  }
  lags.sort((a, b) => a - b);
  const q = (x) => (lags.length ? lags[Math.min(lags.length - 1, Math.floor(lags.length * x))] : NaN);
  const avg = (a) => (a.length ? a.reduce((s, v) => s + v, 0) / a.length : NaN);

  const result = {
    mode: MODE,
    waiters: waiters.length,
    passed: passedAt.size,
    elapsedSec: Number(elapsed.toFixed(1)),
    httpRequests: stats.statusRequests + stats.streamOpens,
    ...stats,
    requestsPerSec: Number(((stats.statusRequests + stats.streamOpens) / elapsed).toFixed(1)),
    pubsubConnectionsKilled: kills,
    scoresSeen: admitScore.size,
    lagMeasured: lags.length,
    lagOver1s: lags.filter((l) => l > 1000).length,
    lagOver5s: lags.filter((l) => l > 5000).length,
    lagMs: { avg: Math.round(avg(lags)), p50: Math.round(q(0.5)), p95: Math.round(q(0.95)), max: Math.round(lags[lags.length - 1] ?? NaN) },
    // 컨테이너 CPU%의 평균(레플리카는 합계). 100%는 코어 하나
    avgCpuPercent: Object.fromEntries(WATCH.map((n) => [n, Number(avg(cpu[n]).toFixed(1))])),
  };
  console.log(JSON.stringify(result, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
