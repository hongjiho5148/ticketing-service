// 장애 시험 한 번을 처음부터 끝까지 돌린다. 시작 전 점검 -> 정합성 기준선 -> 부하 시작 -> 장애 주입 -> 복구 대기 ->
// 정합성 검사 -> 보고서. 결과는 loadtest/results/<시각>-<이름>/ 에 모인다.
//
//   node scripts/chaos/run.mjs --target redis --mode stop --label redis-stop
//   node scripts/chaos/run.mjs --target mysql --mode pause --fault-for 30 --label mysql-pause
//   node scripts/chaos/run.mjs --target redis --mode stop --preflight-only      점검만 하고 끝냄
//
// 부하 생성기는 게이트웨이 속도 제한과 캡차를 풀 수 없으므로 끄고 띄워야 한다(점검이 확인한다):
//   RATE_LIMIT_ENABLED=false CAPTCHA_ENABLED=false docker compose up -d gateway auth-service backend
import { spawn } from "node:child_process";
import { closeSync, createReadStream, createWriteStream, existsSync, fstatSync, mkdirSync, openSync, readFileSync, readSync, rmSync, writeFileSync } from "node:fs";
import { createGzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { COMPOSE_NETWORK, TARGETS, containerOf, docker, iso, mysqlRows, promQuery, redisCli, slackCounters, sleep, vmClockOffsetMs } from "./lib.mjs";
import { MODES, runFault } from "./inject.mjs";
import { checkWithSettle, countViolations, runChecks } from "./invariants.mjs";
import { buildBaselineReport, buildReport, collectAlerts, collectAlertsBetween, collectServerMetrics, collectServerMetricsBetween, loadPointTime, readK6Points } from "./report.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

const { values: args } = parseArgs({
  options: {
    target: { type: "string" },
    mode: { type: "string", default: "stop" },
    "fault-at": { type: "string", default: "60" },
    "fault-for": { type: "string", default: "30" },
    duration: { type: "string", default: "240" },
    users: { type: "string", default: "100" },
    event: { type: "string", default: "auto" },
    prefix: { type: "string", default: "spike" },
    "req-timeout": { type: "string", default: "20s" },
    capacity: { type: "string", default: "500" },
    settle: { type: "string", default: "120" },
    label: { type: "string" },
    baseline: { type: "boolean", default: false }, // 장애 없이 같은 부하만 돌려 정상 상태 수치를 잰다
    "preflight-only": { type: "boolean", default: false },
  },
});
const BASELINE = args.baseline;

const cfg = {
  target: args.target,
  mode: args.mode,
  faultAt: Number(args["fault-at"]),
  faultFor: Number(args["fault-for"]),
  duration: Number(args.duration),
  users: Number(args.users),
  prefix: args.prefix,
  reqTimeout: args["req-timeout"],
  capacity: Number(args.capacity),
  settle: Number(args.settle),
};
cfg.label = args.label ?? (BASELINE ? "baseline" : `${cfg.target}-${cfg.mode}`);

if (BASELINE) {
  if (cfg.duration < 90) {
    console.error("--duration은 90초 이상이어야 합니다(워밍업 30초를 빼고도 값을 낼 수 있게)");
    process.exit(2);
  }
} else {
  if (!cfg.target || !TARGETS[cfg.target]) {
    console.error(`--target이 필요합니다 (가능: ${Object.keys(TARGETS).join(", ")}). 장애 없이 정상 상태만 재려면 --baseline`);
    process.exit(2);
  }
  if (!MODES.includes(cfg.mode)) {
    console.error(`--mode는 ${MODES.join(", ")} 중 하나여야 합니다`);
    process.exit(2);
  }
  // 복구 후 오류율이 돌아오는 것까지 봐야 하므로 장애가 끝난 뒤에도 충분히 부하를 이어 가야 한다.
  const MIN_TAIL = 90;
  if (cfg.duration < cfg.faultAt + cfg.faultFor + MIN_TAIL) {
    console.error(`--duration(${cfg.duration}초)이 너무 짧습니다. 장애 시작(${cfg.faultAt}) + 유지(${cfg.faultFor}) + 복구 관찰(${MIN_TAIL}) = ${cfg.faultAt + cfg.faultFor + MIN_TAIL}초 이상이어야 합니다.`);
    process.exit(2);
  }
}

const ok = (m) => console.log(`  [통과] ${m}`);
const warn = (m) => console.log(`  [주의] ${m}`);
const fail = (m, fix) => {
  console.log(`  [실패] ${m}`);
  if (fix) console.log(`         해결: ${fix}`);
  return false;
};

async function envOf(container, name) {
  const { out } = await docker(["exec", container, "printenv", name], { allowFail: true });
  return out.trim() || null;
}

/** 시험을 시작해도 되는 상태인지 확인한다. 통과하지 못하면 시험 결과가 오염되므로 시작하지 않는다. */
async function preflight() {
  console.log("\n== 시작 전 점검");
  let good = true;

  // 1. 필요한 컨테이너가 떠 있는가
  const { out } = await docker(["ps", "--format", "{{.Names}}"]);
  const running = new Set(out.split("\n").map((s) => s.trim()));
  for (const name of ["ticketing-mysql", "ticketing-redis", "ticketing-nginx", "ticketing-gateway", "ticketing-prometheus", "ticketing-alertmanager"]) {
    if (running.has(name)) ok(`${name} 실행 중`);
    else good = fail(`${name}가 떠 있지 않습니다`, "docker compose up -d") && good;
  }
  if (!good) return null;

  // 2. 속도 제한과 캡차가 꺼져 있는가
  const backend = await containerOf("backend");
  for (const [container, name] of [["ticketing-gateway", "RATE_LIMIT_ENABLED"], [backend, "CAPTCHA_ENABLED"], ["ticketing-auth-service", "CAPTCHA_ENABLED"]]) {
    const value = await envOf(container, name);
    if (value === "false") ok(`${container}: ${name}=false`);
    else if (value === null) warn(`${container}: ${name}이 설정돼 있지 않습니다(기본값에 따름)`);
    else good = fail(`${container}: ${name}=${value}`, "RATE_LIMIT_ENABLED=false CAPTCHA_ENABLED=false docker compose up -d gateway auth-service backend") && good;
  }

  // 3. 모든 서비스를 Prometheus가 수집하고 있는가, 이미 울리는 알림은 없는가
  try {
    const down = (await promQuery("up == 0")).map((r) => r.metric.service ?? r.metric.job);
    if (down.length === 0) ok("Prometheus 수집 대상이 모두 정상");
    else good = fail(`수집이 안 되는 대상: ${down.join(", ")}`, "서비스가 모두 기동한 뒤(1~2분) 다시 시도") && good;
    const firing = await promQuery('ALERTS{alertstate="firing"}');
    if (firing.length === 0) ok("이미 울리고 있는 알림이 없음");
    else warn(`이미 울리는 알림이 있어 감지 시간 측정이 흐려질 수 있습니다: ${firing.map((r) => r.metric.alertname).join(", ")}`);
  } catch (e) {
    good = fail(`Prometheus에 연결할 수 없습니다: ${e.message}`, "docker compose up -d prometheus") && good;
  }

  // 4. Slack으로 보낼 수 있는가(안 돼도 알림 판정은 Prometheus 기준으로 가능하다)
  try {
    await slackCounters();
    const hook = join(ROOT, "monitoring/alertmanager/secrets/slack_webhook_url");
    if (existsSync(hook) && readFileSync(hook, "utf8").startsWith("https://hooks.slack.com/")) ok("Slack 웹훅 파일이 있음");
    else warn("Slack 웹훅 파일이 없거나 형식이 다릅니다. 알림 전달(Slack)은 확인할 수 없습니다");
  } catch {
    good = fail("Alertmanager에 연결할 수 없습니다", "docker compose up -d alertmanager") && good;
  }

  // 5. 시험할 공연과 계정
  let eventId = args.event;
  if (eventId === "auto") {
    const rows = await mysqlRows(`SELECT e.id, COUNT(*) FROM ticketing_event.event e JOIN ticketing_event.seat s ON s.event_id = e.id
      WHERE e.status = 'OPEN' AND e.start_at > NOW() + INTERVAL 3 HOUR AND s.status = 'AVAILABLE'
      GROUP BY e.id ORDER BY COUNT(*) DESC LIMIT 1`);
    if (rows.length === 0) {
      good = fail("판매 중이고 좌석이 남은 공연이 없습니다", "scripts/seed_demo_events.sql 로 데모 공연을 만드세요") && good;
    } else {
      eventId = rows[0][0];
      const seats = Number(rows[0][1]);
      if (seats >= cfg.users * 2) ok(`공연 ${eventId} 사용(빈 좌석 ${seats}개)`);
      else warn(`공연 ${eventId}의 빈 좌석이 ${seats}개뿐입니다. 좌석 충돌이 많아집니다`);
    }
  }
  const [[accounts]] = await mysqlRows(`SELECT COUNT(*) FROM ticketing_auth.users WHERE email LIKE '${cfg.prefix.replace(/[^a-zA-Z0-9_]/g, "")}%@example.com'`);
  if (Number(accounts) >= cfg.users) ok(`${cfg.prefix} 계정 ${accounts}개`);
  else good = fail(`${cfg.prefix} 계정이 ${accounts}개뿐입니다(필요 ${cfg.users}개)`, "loadtest/seed_spike_users.sql 로 계정을 만드세요") && good;

  return good ? { eventId } : null;
}

/** k6의 첫 본 요청이 기록된 시각(호스트 시계 ms)을 기다린다. 이 시각을 부하 시작으로 보고 장애 시각을 잡는다. */
async function waitForLoadStart(file, k6, vmOffsetMs, timeoutMs) {
  let offset = 0;
  let carry = "";
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (k6.exited) throw new Error(`k6가 부하를 시작하기 전에 끝났습니다.\n${k6.tail()}`);
    if (existsSync(file)) {
      const fd = openSync(file, "r");
      const size = fstatSync(fd).size;
      if (size > offset) {
        const buf = Buffer.alloc(size - offset);
        readSync(fd, buf, 0, buf.length, offset);
        offset = size;
        carry += buf.toString("utf8");
        const lines = carry.split("\n");
        carry = lines.pop();
        for (const line of lines) {
          const t = loadPointTime(line);
          if (t !== null) return t - vmOffsetMs;
        }
      }
      closeSync(fd);
    }
    await sleep(500);
  }
  throw new Error(`k6가 ${timeoutMs / 1000}초 안에 부하를 시작하지 않았습니다.\n${k6.tail()}`);
}

async function gzipFile(src, dst) {
  await pipeline(createReadStream(src), createGzip({ level: 6 }), createWriteStream(dst));
  rmSync(src);
}

async function main() {
  const plan = BASELINE
    ? `정상 상태 기준선(장애 없음): 전체 ${cfg.duration}초, ${cfg.users}명`
    : `${cfg.target} ${cfg.mode}: 부하 시작 ${cfg.faultAt}초 뒤 장애, ${cfg.faultFor}초 유지, 전체 ${cfg.duration}초, ${cfg.users}명`;
  console.log(`장애 시험: ${plan}`);

  const checked = await preflight();
  if (!checked) {
    console.log("\n점검을 통과하지 못해 시작하지 않았습니다.");
    process.exit(1);
  }
  if (args["preflight-only"]) {
    console.log("\n점검만 하고 끝냈습니다. 시험은 시작하지 않았습니다.");
    return;
  }

  const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
  const dir = join(ROOT, "loadtest", "results", `${stamp}-${cfg.label}`);
  mkdirSync(dir, { recursive: true });
  console.log(`\n결과 폴더: ${dir}`);

  const vmOffsetMs = await vmClockOffsetMs();
  console.log(`시계 차이(Docker VM - 호스트): ${vmOffsetMs}ms`);

  console.log("\n== 정합성 기준선");
  const baseline = await runChecks({ capacity: cfg.capacity });
  writeFileSync(join(dir, "baseline.json"), JSON.stringify(baseline, null, 2));
  console.log(`기준선에 이미 있는 위반 ${countViolations(baseline)}건(옛 데이터). 시험 후에는 이것을 뺀 새 위반만 봅니다.`);
  const slackBefore = await slackCounters();

  // 부하 시작
  console.log("\n== 부하 시작");
  const k6Name = `chaos-k6-${stamp}`;
  const rawFile = join(dir, "k6.json");
  // docker run [옵션] 이미지 k6 run [k6 옵션] 스크립트
  const dockerRunArgs = [
    "run", "--rm", "--name", k6Name, "--network", COMPOSE_NETWORK, "--ulimit", "nofile=65536:65536",
    "-e", "BASE_URL=http://nginx/api", "-e", `EVENT_ID=${checked.eventId}`, "-e", `VUS=${cfg.users}`,
    "-e", `DURATION=${cfg.duration}s`, "-e", `ACCOUNT_PREFIX=${cfg.prefix}`, "-e", `REQ_TIMEOUT=${cfg.reqTimeout}`,
    "-v", `${join(ROOT, "loadtest")}:/scripts:ro`, "-v", `${dir}:/results`,
    "grafana/k6", "run", "--out", "json=/results/k6.json", "/scripts/chaos_flow.js",
  ];
  const k6 = { exited: false, code: null, output: "", tail() { return this.output.slice(-1500); } };
  const k6Proc = spawn("docker", dockerRunArgs, { stdio: ["ignore", "pipe", "pipe"] });
  const collect = (d) => { k6.output += d.toString("utf8"); if (k6.output.length > 20000) k6.output = k6.output.slice(-10000); };
  k6Proc.stdout.on("data", collect);
  k6Proc.stderr.on("data", collect);
  const k6Done = new Promise((res) => k6Proc.on("close", (code) => { k6.exited = true; k6.code = code; res(code); }));

  let faultStarted = false;
  // k6 컨테이너를 지운다. 끝날 때까지 기다려야 한다. 기다리지 않고 프로그램이 끝나면 부하가 계속 돌아간다.
  const killK6 = () => docker(["rm", "-f", k6Name], { allowFail: true });
  process.once("SIGINT", async () => {
    await killK6();
    if (!faultStarted) process.exit(130);
  });

  let chaos = null; // 기준선 모드에서는 장애를 넣지 않으므로 null
  let loadStartMs;
  try {
    loadStartMs = await waitForLoadStart(rawFile, k6, vmOffsetMs, 180_000);
    console.log(`부하 시작: ${iso(loadStartMs)}`);
    if (!BASELINE) {
      faultStarted = true;
      const waitSec = Math.max(0, cfg.faultAt - (Date.now() - loadStartMs) / 1000);
      chaos = await runFault({ target: cfg.target, mode: cfg.mode, atSec: waitSec, forSec: cfg.faultFor });
      chaos.loadStartMs = loadStartMs;
    }
  } catch (e) {
    await killK6();
    throw e;
  }

  console.log("\n== 부하가 끝나기를 기다립니다");
  await k6Done;
  if (k6.code !== 0) warn(`k6가 종료 코드 ${k6.code}로 끝났습니다. 출력 끝부분:\n${k6.tail()}`);

  console.log("\n== 정합성 검사 (서비스가 스스로 정리하는 시간을 기다립니다)");
  const invariants = await checkWithSettle({ baseline, settleSec: cfg.settle, capacity: cfg.capacity, log: (m) => console.log(`  ${m}`) });
  const slackAfter = await slackCounters();

  if (chaos) writeFileSync(join(dir, "chaos.json"), JSON.stringify(chaos, null, 2));
  const meta = {
    label: cfg.label,
    mode: BASELINE ? "baseline" : "chaos",
    startedAtMs: loadStartMs,
    vmOffsetMs,
    config: { users: cfg.users, durationSec: cfg.duration, event: checked.eventId, reqTimeout: cfg.reqTimeout, faultAt: cfg.faultAt, faultFor: cfg.faultFor },
    invariants: { remaining: invariants.remaining, firstSeen: invariants.firstSeen, waitedSec: invariants.waitedSec },
    slack: { before: slackBefore, after: slackAfter },
  };
  writeFileSync(join(dir, "meta.json"), JSON.stringify(meta, null, 2));
  await gzipFile(rawFile, join(dir, "k6.json.gz"));

  console.log("\n== 보고서 만들기");
  const points = (await readK6Points(join(dir, "k6.json.gz"))).map((p) => ({ ...p, t: p.t - vmOffsetMs }));
  let md;
  if (BASELINE) {
    const endMs = points.reduce((max, p) => (p.t > max ? p.t : max), loadStartMs);
    const alerts = await collectAlertsBetween(loadStartMs, endMs + 60_000, vmOffsetMs).catch(() => null);
    const serverMetrics = await collectServerMetricsBetween(loadStartMs, endMs, vmOffsetMs).catch(() => null);
    md = buildBaselineReport({ meta, points, alerts, serverMetrics, invariants: meta.invariants, loadStartMs });
  } else {
    const alerts = await collectAlerts(chaos, vmOffsetMs).catch(() => null);
    const serverMetrics = await collectServerMetrics(chaos, vmOffsetMs).catch(() => null);
    md = buildReport({ meta, chaos, points, alerts, serverMetrics, invariants: meta.invariants });
  }
  writeFileSync(join(dir, "report.md"), md);
  console.log(md.split("\n").slice(0, 16).join("\n"));
  console.log(`\n전체 보고서: ${join(dir, "report.md")}`);
}

main().catch((e) => {
  console.error(`\n오류: ${e.message}`);
  process.exit(1);
});
