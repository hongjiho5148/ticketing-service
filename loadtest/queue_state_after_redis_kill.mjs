// Redis가 죽었다 살아난 뒤, 줄에 서 있던 사람과 입장권을 든 사람의 상태가 어떻게 되는지 본다.
//
//   node loadtest/queue_state_after_redis_kill.mjs --event 277 --users 30 --mode kill
//   node loadtest/queue_state_after_redis_kill.mjs --event 278 --users 30 --mode stop     (정상 종료: 데이터가 보존되어야 한다)
//
// 장애 시험 부하(chaos_flow.js)의 사용자는 1초짜리 짧은 흐름을 반복해서, Redis의 데이터가 사라져도 그것을 나중에 쓰는 사람이
// 없다. 이 시험은 오래 사는 상태(줄 번호와 입장권)를 일부러 만들어 두고 Redis를 멈춘 뒤 그 상태가 남아 있는지를 본다.
//
// 준비: 대기열 정원을 낮춰 backend를 띄운다(예: QUEUE_ACTIVE_CAPACITY=10). 그래야 일부는 입장하고 나머지는 줄에 선다.
//       속도 제한과 캡차는 꺼져 있어야 한다. 공연마다 대기열이 따로라서, 시험마다 다른 --event를 쓴다.
import { execFile } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { promisify } from "node:util";

const run = promisify(execFile);
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const { values: args } = parseArgs({
  options: {
    event: { type: "string" },
    users: { type: "string", default: "30" },
    mode: { type: "string", default: "kill" },
    base: { type: "string", default: "http://localhost:8080/api" },
    prefix: { type: "string", default: "spike" },
    "down-seconds": { type: "string", default: "5" },
  },
});
if (!args.event) {
  console.error("--event <공연 id>가 필요합니다");
  process.exit(2);
}
const EVENT = Number(args.event);
const USERS = Number(args.users);
const BASE = args.base;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const redisCli = async (...a) => (await run("docker", ["exec", "ticketing-redis", "redis-cli", ...a])).stdout.trim();

async function api(method, path, { token, body, headers } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    // 본문 없음
  }
  return { status: res.status, json };
}

async function login(n) {
  const r = await api("POST", "/auth/login", { body: { email: `${args.prefix}${n}@example.com`, password: "password123" } });
  if (r.status !== 200) throw new Error(`로그인 실패(${args.prefix}${n}): ${r.status}. 계정이 있는지, 캡차가 꺼져 있는지 확인하세요.`);
  return r.json.accessToken;
}

function tally(items, key) {
  const m = new Map();
  for (const it of items) m.set(key(it), (m.get(key(it)) ?? 0) + 1);
  return [...m.entries()].map(([k, v]) => `${k} ${v}명`).join(", ") || "없음";
}

async function statusOf(u) {
  const r = await api("GET", `/queue/status?queueToken=${u.queueToken}`, { token: u.token });
  return { http: r.status, state: r.status === 200 ? r.json.status : r.json?.code ?? "?", passToken: r.json?.passToken ?? null, rank: r.json?.rankNo };
}

console.log(`공연 ${EVENT}, 사용자 ${USERS}명, Redis ${args.mode}`);

// 1. 로그인
const users = [];
for (let n = 1; n <= USERS; n++) users.push({ n, token: await login(n) });

// 2. 지금 스냅샷을 한 번 저장해서 기준을 만든다. 이 뒤에 생기는 상태는 저장되지 않은 상태가 된다.
await redisCli("bgsave");
for (let i = 0; i < 50; i++) {
  if ((await redisCli("info", "persistence")).includes("rdb_bgsave_in_progress:0")) break;
  await sleep(200);
}
const lastSaveAt = Number(await redisCli("lastsave"));

// 3. 모두 줄을 선다
const enters = await Promise.all(users.map((u) => api("POST", "/queue/enter", { token: u.token, body: { eventId: EVENT } })));
users.forEach((u, i) => {
  u.queueToken = enters[i].json?.queueToken ?? null;
  u.enterHttp = enters[i].status;
});
const failedEnter = users.filter((u) => !u.queueToken);
if (failedEnter.length) throw new Error(`줄 서기에 실패한 사용자 ${failedEnter.length}명(HTTP ${[...new Set(failedEnter.map((u) => u.enterHttp))]})`);

// 4. 입장이 정리될 때까지 잠깐 기다린다(입장 처리는 0.3초마다 돈다)
await sleep(2500);
const before = await Promise.all(users.map(statusOf));
users.forEach((u, i) => (u.before = before[i]));
const changes = (await redisCli("info", "persistence")).match(/rdb_changes_since_last_save:(\d+)/)?.[1];
console.log(`\n[장애 전] ${tally(before, (b) => b.state)}  (스냅샷 이후 저장 안 된 변경 ${changes}건, 스냅샷 ${Math.round(Date.now() / 1000 - lastSaveAt)}초 전)`);

// 5. 입장한 사람 몇 명이 좌석을 잡을 수 있는지 장애 전에 확인해 둔다(입장권이 유효하다는 기준)
const passed = users.filter((u) => u.before.state === "PASSED");
const seatsRes = await api("GET", `/events/${EVENT}/seats`, { token: users[0].token });
const freeSeats = (seatsRes.json ?? []).filter((s) => s.status === "AVAILABLE").slice(0, 10).map((s) => s.id);

// 6. Redis를 멈춘다(정상 종료 또는 강제 종료). inject.mjs가 되돌리고 준비될 때까지 기다린다.
console.log(`\nRedis ${args.mode} ${args["down-seconds"]}초 ...`);
const t0 = Date.now();
await run(process.execPath, [join(ROOT, "scripts/chaos/inject.mjs"), "--target", "redis", "--mode", args.mode, "--at", "0", "--for", args["down-seconds"]], { cwd: ROOT });
console.log(`Redis가 다시 준비됨 (${((Date.now() - t0) / 1000).toFixed(1)}초 걸림). 클라이언트가 다시 연결하도록 3초 기다린다.`);
await sleep(3000);

// 7. 같은 토큰으로 상태를 다시 물어본다
const after = await Promise.all(users.map(statusOf));
users.forEach((u, i) => (u.after = after[i]));
console.log(`\n[장애 후] ${tally(after, (a) => `${a.state}(HTTP ${a.http})`)}`);

const waitingBefore = users.filter((u) => u.before.state === "WAITING");
const kept = (group) => group.filter((u) => u.after.http === 200).length;
console.log("\n| 장애 전 상태 | 사람 수 | 장애 후에도 같은 토큰이 유효 | 사라짐 |");
console.log("|---|--:|--:|--:|");
console.log(`| 입장함(PASSED) | ${passed.length} | ${kept(passed)} | ${passed.length - kept(passed)} |`);
console.log(`| 줄에서 기다림(WAITING) | ${waitingBefore.length} | ${kept(waitingBefore)} | ${waitingBefore.length - kept(waitingBefore)} |`);

// 8. 입장했던 사람이 실제로 좌석을 잡을 수 있는가
if (passed.length && freeSeats.length) {
  console.log("\n입장했던 사람이 입장권으로 좌석을 잡으면:");
  const tries = passed.slice(0, Math.min(5, freeSeats.length));
  const results = [];
  for (let i = 0; i < tries.length; i++) {
    const u = tries[i];
    const r = await api("POST", "/reservations", { token: u.token, body: { seatId: freeSeats[i] }, headers: { "X-Pass-Token": u.before.passToken } });
    results.push({ http: r.status, code: r.json?.code ?? (r.status === 201 ? "OK" : "?") });
    if (r.status === 201) await api("DELETE", `/reservations/${r.json.reservationId}`, { token: u.token });
  }
  console.log(`  ${tally(results, (x) => `HTTP ${x.http} ${x.code}`)}`);
}
