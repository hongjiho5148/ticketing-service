// 장애 시험 도구들이 함께 쓰는 보조 함수. docker CLI, MySQL, Redis, Prometheus, Alertmanager를 다룬다.
// 외부 패키지 없이 Node 내장 기능만 쓴다(Node 20 이상).
import { spawn } from "node:child_process";

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export const PROM_URL = process.env.PROM_URL ?? "http://127.0.0.1:9091";
export const ALERTMANAGER_URL = process.env.ALERTMANAGER_URL ?? "http://127.0.0.1:9093";
export const COMPOSE_NETWORK = process.env.COMPOSE_NETWORK ?? "ticketing-service_default";

/** 장애를 넣을 수 있는 대상. 모니터링 컨테이너(Prometheus, Alertmanager, Grafana)는 일부러 뺐다. 시험을 지켜보는 쪽이 같이 죽으면 안 된다. */
export const TARGETS = {
  mysql: { container: "ticketing-mysql" },
  redis: { container: "ticketing-redis" },
  kafka: { container: "ticketing-kafka" },
  gateway: { container: "ticketing-gateway", promService: "gateway" },
  "auth-service": { container: "ticketing-auth-service", promService: "auth-service" },
  "event-service": { container: "ticketing-event-service", promService: "event-service" },
  "reservation-service": { container: "ticketing-reservation-service", promService: "reservation-service" },
  "order-service": { container: "ticketing-order-service", promService: "order-service" },
  // backend는 복제본이 여러 개일 수 있고 이름에 번호가 붙으므로 compose 서비스 이름으로 찾는다.
  backend: { composeService: "backend", promJob: "backend" },
};

/** docker CLI를 실행한다. 입력은 UTF-8로 표준 입력에 넣는다(Windows 터미널의 문자 인코딩에 영향받지 않게). */
export function docker(args, { input, allowFail = false } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn("docker", args, { stdio: ["pipe", "pipe", "pipe"] });
    let out = "";
    let err = "";
    child.stdout.on("data", (d) => (out += d.toString("utf8")));
    child.stderr.on("data", (d) => (err += d.toString("utf8")));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0 && !allowFail) {
        reject(new Error(`docker ${args.join(" ")} 실패(종료 코드 ${code}): ${err.trim()}`));
      } else {
        resolve({ code, out, err });
      }
    });
    child.stdin.end(input === undefined ? undefined : Buffer.from(input, "utf8"));
  });
}

/** 대상 이름을 실제 컨테이너 이름으로 바꾼다. */
export async function containerOf(target) {
  const spec = TARGETS[target];
  if (!spec) {
    throw new Error(`알 수 없는 대상: ${target} (가능: ${Object.keys(TARGETS).join(", ")})`);
  }
  if (spec.container) return spec.container;
  const { out } = await docker(["ps", "--filter", `label=com.docker.compose.service=${spec.composeService}`, "--format", "{{.Names}}"]);
  const name = out.split("\n").map((s) => s.trim()).filter(Boolean)[0];
  if (!name) throw new Error(`${target} 컨테이너가 떠 있지 않습니다`);
  return name;
}

/** SQL을 실행하고 행을 배열의 배열로 돌려준다(탭으로 나뉜 값). SQL은 표준 입력으로 넣는다. */
export async function mysqlRows(sql) {
  const { out } = await docker(
    ["exec", "-i", "ticketing-mysql", "mysql", "-uroot", "-proot", "--default-character-set=utf8mb4", "-N", "-B"],
    { input: `${sql.trim().replace(/;+$/, "")};\n` },
  );
  return out.split("\n").filter(Boolean).map((line) => line.split("\t"));
}

export async function redisCli(args) {
  const { out } = await docker(["exec", "ticketing-redis", "redis-cli", ...args]);
  return out.trim();
}

export async function promQuery(query, atSec) {
  const url = new URL("/api/v1/query", PROM_URL);
  url.searchParams.set("query", query);
  if (atSec !== undefined) url.searchParams.set("time", String(atSec));
  const body = await (await fetch(url)).json();
  if (body.status !== "success") throw new Error(`Prometheus 질의 실패: ${query}`);
  return body.data.result;
}

export async function promRange(query, startSec, endSec, stepSec = 5) {
  const url = new URL("/api/v1/query_range", PROM_URL);
  url.searchParams.set("query", query);
  url.searchParams.set("start", String(startSec));
  url.searchParams.set("end", String(endSec));
  url.searchParams.set("step", String(stepSec));
  const body = await (await fetch(url)).json();
  if (body.status !== "success") throw new Error(`Prometheus 범위 질의 실패: ${query}`);
  return body.data.result;
}

/** Alertmanager가 Slack으로 보낸 알림 수와 실패 수. 시험 전후로 재서 차이를 본다. */
export async function slackCounters() {
  const text = await (await fetch(`${ALERTMANAGER_URL}/metrics`)).text();
  const pick = (name) => {
    const re = new RegExp(`^${name}\\{[^}]*integration="slack"[^}]*\\}\\s+(\\S+)`, "m");
    const m = text.match(re);
    return m ? Number(m[1]) : 0;
  };
  return { sent: pick("alertmanager_notifications_total"), failed: pick("alertmanager_notifications_failed_total") };
}

/**
 * 호스트 시계와 Docker VM 시계의 차이(ms, VM - 호스트)를 잰다. k6와 Prometheus의 시각은 VM 시계를 쓰고
 * 장애를 넣는 시각은 호스트 시계라서, 둘을 맞춰야 "장애 후 몇 초"를 말할 수 있다. 초 단위 정밀도라 오차는 약 ±1초다.
 */
export async function vmClockOffsetMs() {
  const samples = [];
  for (let i = 0; i < 5; i++) {
    const t1 = Date.now();
    const { out } = await docker(["exec", "ticketing-redis", "date", "+%s"]);
    const t2 = Date.now();
    samples.push(Number(out.trim()) * 1000 - (t1 + t2) / 2);
    await sleep(200);
  }
  samples.sort((a, b) => a - b);
  // date +%s는 초 아래를 버리므로 평균 0.5초만큼 작게 나온다. 그만큼 보정한다.
  return Math.round(samples[Math.floor(samples.length / 2)] + 500);
}

export const iso = (ms) => new Date(ms).toISOString();
