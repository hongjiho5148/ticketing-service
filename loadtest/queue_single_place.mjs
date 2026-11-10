// 대기열에 많은 사람이 한꺼번에 들어올 때 두 가지를 확인한다.
//   1) 사용자·공연당 줄 자리는 하나다: 사람마다 입장 요청을 DUP번 동시에 보내도(더블 클릭, 새로고침, 개발 모드 이중 실행) 대기표는 하나다.
//   2) 예상 대기시간(앞사람 수로 추정한 상한)이 실제 대기와 얼마나 맞는가: 들어올 때 받은 추정과 입장까지 걸린 시간을 순번 구간별로 비교한다.
//
//   준비: spikeN@example.com 계정이 필요하다 -> loadtest/seed_spike_users.sql
//         backend를 작은 정원과 짧은 입장권으로 띄워 줄이 실제로 생기게 한다(예: QUEUE_ACTIVE_CAPACITY=100, QUEUE_PASS_TOKEN_TTL_MS=20000,
//         QUEUE_PASS_EXTENSION_MS=0). 정원이 사용자 수 이상이면 모두 바로 입장해서 줄이 생기지 않는다.
//         로그인 캡차와 레이트 리미팅은 끈다(CAPTCHA_ENABLED=false RATE_LIMIT_ENABLED=false).
//   실행: node loadtest/queue_single_place.mjs --users=500 --event=275 --capacity=100 --dup=2
//
// 입장 뒤에는 아무것도 하지 않는다(좌석을 잡지 않음). 슬롯은 입장권이 끝나면 돌아온다.
import { execFileSync } from "node:child_process";

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split("=")[1] : fallback;
};
const BASE = arg("base", "http://localhost:8080/api");
const USERS = Number(arg("users", 500));
const EVENT = Number(arg("event", 275));
const CAPACITY = Number(arg("capacity", 100)); // 순번 구간을 나누는 데만 쓴다. backend의 QUEUE_ACTIVE_CAPACITY와 같게 준다
const DUP = Number(arg("dup", 2)); // 사람마다 동시에 보내는 입장 요청 수
const POLL_S = Number(arg("poll", 2));
const MAX_WAIT_S = Number(arg("maxwait", 300));
const PROM = arg("prom", "http://127.0.0.1:9091"); // localhost는 IPv6(::1)로 풀려서 닿지 않을 수 있다
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toTimeString().slice(0, 8), ...a);

async function call(method, path, token, body) {
  const started = Date.now();
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 본문이 JSON이 아님 */ }
    return { status: res.status, json, ms: Date.now() - started };
  } catch (e) {
    return { status: 0, json: null, ms: Date.now() - started, error: String(e) };
  }
}

function redisCount(key) {
  try {
    return Number(execFileSync("docker", ["exec", "ticketing-redis", "redis-cli", "zcard", key], { encoding: "utf8" }).trim());
  } catch {
    return NaN;
  }
}

const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] : NaN);

// 1) 로그인은 오픈 전에 미리 해 둔다(측정 밖)
log(`로그인 ${USERS}명 ...`);
const people = [];
for (let from = 1; from <= USERS; from += 25) {
  const batch = [];
  for (let n = from; n < from + 25 && n <= USERS; n++) {
    batch.push(call("POST", "/auth/login", null, { email: `spike${n}@example.com`, password: "password123" }).then((r) => ({ n, r })));
  }
  for (const { n, r } of await Promise.all(batch)) {
    if (r.status !== 200) throw new Error(`spike${n} 로그인 실패(${r.status}): seed_spike_users.sql을 먼저 실행하고 캡차/레이트 리미팅을 끄세요`);
    people.push({ n, jwt: r.json.accessToken, tokens: new Set(), enterRank: null, enterEstimate: null, enteredAt: 0, passedAt: null, errors: 0 });
  }
}
const waitingKey = `queue:waiting:${EVENT}`;
const activeKey = `queue:active:${EVENT}`;
log(`시작 전 줄: 대기 ${redisCount(waitingKey)} / 입장 ${redisCount(activeKey)}`);

// 2) 한꺼번에 입장: 사람마다 DUP번을 동시에 보낸다
const t0 = Date.now();
log(`입장 요청 ${USERS * DUP}개를 동시에 보냅니다(${USERS}명 x ${DUP}번)`);
const enterMs = [];
let enterFailed = 0;
async function enterPerson(p) {
  {
    const results = await Promise.all(
      Array.from({ length: DUP }, () => call("POST", "/queue/enter", p.jwt, { eventId: EVENT }).then((r) => ({ ...r, at: Date.now() }))),
    );
    for (const r of results) {
      enterMs.push(r.ms);
      if (r.status === 200) {
        p.tokens.add(r.json.queueToken);
        // 입장 요청이 서버에서 기다린 시간은 대기가 아니다. 추정을 받은 순간(첫 응답)부터 입장까지를 실제 대기로 센다.
        // 순번 0은 이미 입장해 있는 자리를 돌려받은 응답이라 추정이 없다.
        if (r.json.rankNo > 0 && (p.enterRank === null || r.at < p.enteredAt)) {
          p.enterRank = r.json.rankNo;
          p.enterEstimate = r.json.estimatedWaitSeconds;
          p.enteredAt = r.at;
        }
      } else {
        enterFailed++;
        p.errors++;
      }
    }
  }
}

// 3) 입장할 때까지 2초마다 묻는다(스트림을 못 쓸 때와 같은 방식). 줄의 크기도 5초마다 읽는다.
// 각자 자기 입장 요청이 끝나는 대로 묻기 시작한다. 다른 사람의 입장 요청이 끝나기를 기다리지 않는다.
let lastSample = 0;
let entered = 0;
const anomalies = []; // 대기표를 찾을 수 없다는 응답을 받은 사람
const pollGaps = []; // 같은 사람이 연이어 묻는 사이의 간격(ms)
const otherStatuses = new Map(); // 200과 404가 아닌 응답
const deadline = t0 + MAX_WAIT_S * 1000;
async function waitPerson(p) {
  {
    const token = [...p.tokens][0];
    if (!token) return;
    let lastRank = null;
    let lastPollAt = p.enteredAt;
    while (Date.now() < deadline) {
      const polledAt = Date.now();
      const s = await call("GET", `/queue/status?queueToken=${token}`, p.jwt);
      pollGaps.push(polledAt - lastPollAt);
      lastPollAt = polledAt;
      if (s.status === 200 && s.json.status === "PASSED") {
        p.passedAt = Date.now();
        return;
      }
      if (s.status === 200) lastRank = s.json.rankNo;
      if (s.status === 404) {
        // 줄에서 빠졌다. 누가 언제 어떤 상태에서 그 응답을 받았는지 남긴다.
        p.errors++;
        anomalies.push(`spike${p.n}: 입장 ${((polledAt - p.enteredAt) / 1000).toFixed(1)}초 뒤 404, 마지막 순번 ${lastRank}, 응답 ${s.ms}ms, 바로 앞 폴링과의 간격 ${pollGaps[pollGaps.length - 1]}ms ${JSON.stringify(s.json)}`);
        return;
      }
      if (s.status !== 200) otherStatuses.set(s.status, (otherStatuses.get(s.status) ?? 0) + 1); // 5xx나 연결 실패는 조용히 다시 묻는다
      if (Date.now() - lastSample > 5000) {
        lastSample = Date.now();
        log(`  ${((Date.now() - t0) / 1000).toFixed(0)}초: 대기 ${redisCount(waitingKey)} / 입장 ${redisCount(activeKey)}`);
      }
      await sleep(POLL_S * 1000 * (0.8 + Math.random() * 0.4));
    }
  }
}

await Promise.all(
  people.map(async (p) => {
    await enterPerson(p);
    if (++entered === USERS) {
      log(`입장 요청 끝(${((Date.now() - t0) / 1000).toFixed(1)}초). 실패 ${enterFailed}건`);
      log(`줄: 대기 ${redisCount(waitingKey)} / 입장 ${redisCount(activeKey)}`);
    }
    await waitPerson(p);
  }),
);
const total = ((Date.now() - t0) / 1000).toFixed(1);

// 4) 결과
const unique = new Set(people.flatMap((p) => [...p.tokens]));
const withOne = people.filter((p) => p.tokens.size === 1).length;
const passed = people.filter((p) => p.passedAt !== null);
console.log("\n===== 결과 =====");
console.log(`사람 ${USERS}명, 입장 요청 ${USERS * DUP}개(사람당 ${DUP}번 동시), 전체 ${total}초`);
console.log(`대기표를 하나만 받은 사람: ${withOne} / ${USERS}   (서로 다른 대기표 ${unique.size}개, 기대값 ${USERS})`);
console.log(`입장한 사람: ${passed.length} / ${USERS}   입장 요청 실패 ${enterFailed}건, 상태 오류 ${people.reduce((a, p) => a + p.errors, 0) - enterFailed}건`);
const em = [...enterMs].sort((a, b) => a - b);
console.log(`입장 요청 응답 시간(ms): p50 ${pct(em, 50)}  p95 ${pct(em, 95)}  최대 ${em[em.length - 1]}`);
console.log(`줄(Redis) 끝: 대기 ${redisCount(waitingKey)} / 입장 ${redisCount(activeKey)}`);
const gaps = [...pollGaps].sort((a, b) => a - b);
console.log(`폴링 간격(ms): p50 ${pct(gaps, 50)}  p99 ${pct(gaps, 99)}  최대 ${gaps[gaps.length - 1]}   (설정 ${POLL_S * 1000}ms 안팎)`);
console.log(`200/404 외 응답: ${otherStatuses.size ? JSON.stringify([...otherStatuses]) : "없음"}`);
for (const a of anomalies) console.log(`  ! ${a}`);

// 순번은 들어오는 동안 앞사람이 입장하면서 계속 줄어들어(같은 순간에 들어온 사람도 순번이 겹친다) 구간으로 묶기 어렵다.
// 대신 사람마다 받은 추정값 그대로 묶어서, "추정이 N초였던 사람들은 실제로 얼마나 기다렸나"를 본다.
const measured = passed.filter((p) => p.enterRank !== null);
console.log(`\n받은 예상 대기시간별 실제 대기(초, 응답을 받은 순간부터 입장까지) — 정원 ${CAPACITY}:`);
console.log("추정(초)   인원   실제(최소 / 중앙 / 최대)   실제가 추정보다 긴 사람(+3초 넘게)");
const byEstimate = new Map();
for (const p of measured) {
  if (!byEstimate.has(p.enterEstimate)) byEstimate.set(p.enterEstimate, []);
  byEstimate.get(p.enterEstimate).push(p);
}
const rows = [...byEstimate.entries()].sort((a, b) => a[0] - b[0]);
// 추정이 1~19초로 흩어져 나오는 사람들(입장이 시작되는 동안 들어온 사람)은 한 줄로 묶는다
const small = rows.filter(([e]) => e < 10);
const rest = rows.filter(([e]) => e >= 10);
const groups = [];
if (small.length) groups.push(["10초 미만", small.flatMap(([, l]) => l), 10]);
for (const [e, l] of rest) groups.push([String(e), l, e]);
for (const [label, list, bound] of groups) {
  const actual = list.map((p) => (p.passedAt - p.enteredAt) / 1000).sort((a, b) => a - b);
  const longer = list.filter((p) => (p.passedAt - p.enteredAt) / 1000 > p.enterEstimate + 3).length;
  console.log(
    `${label.padEnd(10)}${String(list.length).padStart(5)}   ${actual[0].toFixed(1).padStart(5)} / ${pct(actual, 50).toFixed(1).padStart(5)} / ${actual[actual.length - 1].toFixed(1).padStart(5)}        ${longer}/${list.length}`,
  );
}
const received = measured.length;
const within = measured.filter((p) => (p.passedAt - p.enteredAt) / 1000 <= p.enterEstimate + 3).length;
console.log(`추정 + 3초 안에 입장한 사람: ${within} / ${received}`);

// 5) 서버가 센 값(Prometheus가 10초마다 수집하므로 잠깐 기다린다)
await sleep(12000);
for (const metric of ["queue_entered_total", "queue_reentered_total"]) {
  try {
    const res = await fetch(`${PROM}/api/v1/query?query=${metric}`);
    const body = await res.json();
    const sum = body.data.result.reduce((a, r) => a + Number(r.value[1]), 0);
    console.log(`${metric}: ${sum}`);
  } catch {
    console.log(`${metric}: (Prometheus를 읽지 못함)`);
  }
}
