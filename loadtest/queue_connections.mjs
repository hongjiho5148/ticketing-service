// 대기열 SSE 연결을 한꺼번에 많이 열어 두고 서버가 어디까지 버티는지 봅니다(인스턴스당 연결 상한 queue.stream.max-connections 검증).
//
//   node loadtest/queue_connections.mjs --connections=6000 --event=54 --hold=45000
//
// 준비: backend는 1개(--scale backend=1)로, 아무도 입장하지 못하게 QUEUE_ACTIVE_CAPACITY=1(첫 사람만 입장)로 띄우고,
// nginx의 IP당 연결 제한(limit_conn queue_stream_per_ip)은 연결 수보다 크게 올려 둡니다(한 PC에서 모두 여니까).
// 로그인 캡차와 레이트 리미팅은 끕니다. Prometheus(localhost:9091)가 떠 있으면 연결 수마다 메모리·스레드·FD를 같이 읽습니다.
//
// 재는 것: 연결이 열린 수 / 503(가득 참)으로 거절된 수 / 그 밖의 실패, 연결 수에 따른 backend 힙·스레드·열린 파일, nginx 오류,
// 연결이 가득 찬 상태에서 입장 알림이 얼마나 빨리 오는지, 거절된 사람이 폴링으로 이어서 기다릴 수 있는지.
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const args = Object.fromEntries(process.argv.slice(2).map((a) => a.replace(/^--/, "").split("=")));
const CONNECTIONS = Number(args.connections ?? 6000);
const EVENT_ID = Number(args.event ?? 54);
const HOLD_MS = Number(args.hold ?? 45000);
const ACCOUNTS = Number(args.accounts ?? 20);
const BASE = args.base ?? "http://localhost:8080/api";
// 스트림만 다른 주소(예: http://backend:8080/api, nginx를 건너뛰고 backend에 직접)로 열어 어느 구간이 한도인지 가를 수 있다
const STREAM_BASE = args.streambase ?? BASE;
const PROM = args.prom ?? "http://127.0.0.1:9091"; // Node는 localhost를 IPv6로 먼저 시도해 실패하는 경우가 있다
const REDIS = args.redis ?? "ticketing-redis";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function json(method, path, token, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}`);
  return res.json();
}

async function prom(query) {
  try {
    const r = await (await fetch(`${PROM}/api/v1/query?query=${encodeURIComponent(query)}`)).json();
    return r.data.result.reduce((s, x) => s + Number(x.value[1]), 0);
  } catch {
    return NaN;
  }
}

async function snapshot(label) {
  // Prometheus가 10초마다 긁으므로 값이 갱신될 때까지 기다린다
  await sleep(12000);
  const s = {
    label,
    streams: await prom('sum(queue_stream_connections)'),
    heapMB: Math.round((await prom('sum(jvm_memory_used_bytes{application="backend",area="heap"})')) / 1048576),
    threads: await prom('sum(jvm_threads_live_threads{application="backend"})'),
    openFiles: await prom('sum(process_files_open_files{application="backend"})'),
    cpu: Number((await prom('sum(process_cpu_usage{application="backend"})')).toFixed(2)),
  };
  try {
    const { stdout } = await execFileAsync("docker", ["stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}"], { encoding: "utf8" });
    s.containerMem = Object.fromEntries(
      stdout.trim().split("\n").map((l) => l.split(" ")).filter(([n]) => /backend|nginx/.test(n)).map(([n, m]) => [n, m]),
    );
  } catch {}
  console.log(JSON.stringify(s));
  return s;
}

const outcome = { opened: 0, refused503: 0, otherStatus: {}, errors: {}, closedEarly: 0, statusEvents: 0, passed: 0 };
const passedAt = [];

async function openStream(w, stop) {
  try {
    const res = await fetch(`${STREAM_BASE}/queue/stream?queueToken=${w.queueToken}`, {
      headers: { Authorization: `Bearer ${w.jwt}`, Accept: "text/event-stream" },
    });
    if (res.status === 503) {
      outcome.refused503++;
      w.refused = true;
      await res.body?.cancel();
      return;
    }
    if (!res.ok) {
      outcome.otherStatus[res.status] = (outcome.otherStatus[res.status] ?? 0) + 1;
      await res.body?.cancel();
      return;
    }
    outcome.opened++;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!stop.done) {
      const { done, value } = await reader.read();
      if (done) {
        outcome.closedEarly++;
        return;
      }
      buffer += decoder.decode(value, { stream: true });
      let cut;
      while ((cut = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, cut);
        buffer = buffer.slice(cut + 2);
        if (!block.includes("data:")) continue;
        outcome.statusEvents++;
        if (block.includes('"PASSED"')) {
          outcome.passed++;
          passedAt.push(Date.now());
        }
      }
    }
    await reader.cancel();
  } catch (e) {
    const why = String(e.cause?.code ?? e.message).slice(0, 50);
    outcome.errors[why] = (outcome.errors[why] ?? 0) + 1;
  }
}

async function main() {
  const jwts = [];
  for (let i = 1; i <= ACCOUNTS; i++) {
    jwts.push((await json("POST", "/auth/login", null, { email: `loadtest${i}@example.com`, password: "password123" })).accessToken);
  }

  console.log(`entering ${CONNECTIONS} waiters ...`);
  const waiters = [];
  for (let i = 0; i < CONNECTIONS; i += 100) {
    const batch = await Promise.all(
      Array.from({ length: Math.min(100, CONNECTIONS - i) }, async (_, k) => {
        const jwt = jwts[(i + k) % jwts.length];
        const e = await json("POST", "/queue/enter", jwt, { eventId: EVENT_ID });
        return { jwt, queueToken: e.queueToken };
      }),
    );
    waiters.push(...batch);
  }
  const before = await snapshot("before streams");

  const stop = { done: false };
  const t0 = Date.now();
  const tasks = [];
  for (let i = 0; i < waiters.length; i += 200) {
    for (const w of waiters.slice(i, i + 200)) tasks.push(openStream(w, stop));
    await sleep(150); // 한꺼번에 몰리지 않게 조금씩 연다
  }
  console.log(`opened in ${((Date.now() - t0) / 1000).toFixed(1)}s:`, JSON.stringify(outcome));
  await sleep(3000);
  const peak = await snapshot("all streams open");

  // 연결이 가득 찬 채로 입장 알림이 얼마나 빨리 오는지: 앞줄 한 사람을 입장시킨다. docker가 있으면 여기서 직접 하고,
  // 컨테이너 안에서 돌릴 때는 밖에서 이 줄이 찍힌 직후 ZREMRANGEBYRANK queue:active:<event> 0 0 을 실행한다(그 시각을 따로 적어 둔다).
  const before503 = outcome.refused503;
  passedAt.length = 0; // 열 때 이미 입장해 있던 사람의 알림은 빼고
  console.log("READY-TO-FREE");
  let freed = NaN;
  try {
    freed = Date.now();
    await execFileAsync("docker", ["exec", REDIS, "redis-cli", "ZREMRANGEBYRANK", `queue:active:${EVENT_ID}`, "0", "0"]);
  } catch {
    freed = NaN;
  }
  await sleep(12000);
  const lag = passedAt.length && !Number.isNaN(freed) ? passedAt[0] - freed : NaN;
  const firstPassAt = passedAt[0] ?? null;

  // 거절된 사람이 폴링으로 이어서 기다릴 수 있는가
  const refused = waiters.find((w) => w.refused);
  let pollOk = null;
  if (refused) {
    try {
      pollOk = (await json("GET", `/queue/status?queueToken=${refused.queueToken}`, refused.jwt)).status;
    } catch (e) {
      pollOk = String(e.message);
    }
  }

  await sleep(Math.max(0, HOLD_MS - (Date.now() - t0)));
  const held = await snapshot("after hold");
  stop.done = true;
  await Promise.allSettled(tasks);
  await sleep(40000); // 끊긴 연결은 15초마다의 빈 줄 전송이 실패하면서 정리된다
  const after = await snapshot("after close");

  let nginxErrors = "n/a";
  try {
    const { stdout, stderr } = await execFileAsync("docker", ["logs", "ticketing-nginx"], { encoding: "utf8", maxBuffer: 1 << 28 });
    nginxErrors = ((stdout + stderr).match(/worker_connections are not enough|accept4\(\) failed|Too many open files/g) ?? []).length;
  } catch {}

  console.log(
    JSON.stringify(
      {
        connections: CONNECTIONS,
        outcome,
        perConnection: {
          heapKB: Number((((peak.heapMB - before.heapMB) * 1024) / Math.max(1, outcome.opened)).toFixed(1)),
          extraThreads: peak.threads - before.threads,
          extraOpenFiles: peak.openFiles - before.openFiles,
        },
        passPushLagMsWithFullConnections: lag,
        firstPassAtMs: firstPassAt,
        refusedClientCanPoll: pollOk,
        refusedAfterFreeing: outcome.refused503 - before503,
        nginxConnectionErrors: nginxErrors,
        streamsAfterClose: after.streams,
        heldStreams: held.streams,
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
