// 장애 시험 결과를 한 장의 보고서(Markdown)로 만든다. 재료는 시험 폴더(loadtest/results/<id>/)에 저장된 파일들이다.
//
//   node scripts/chaos/report.mjs --dir loadtest/results/<id> [--no-prom]
//
//   k6.json.gz  k6가 요청마다 남긴 기록(상태 코드, 지연)  -> 사용자가 장애 중에 겪은 일
//   chaos.json  장애를 넣고 되돌린 실제 시각              -> 구간을 나누는 기준
//   meta.json   시험 조건, 시계 차이, 정합성 결과, Slack 전송 수
//   Prometheus  알림이 울린 시각, 서버 쪽 지표 최대값
//
// 계산 부분은 순수 함수로 두고 내보낸다(report.test.mjs가 검사한다).
import { createReadStream, readFileSync, writeFileSync } from "node:fs";
import { createGunzip } from "node:zlib";
import { createInterface } from "node:readline";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { promRange } from "./lib.mjs";

// ---------- 순수 계산 ----------

/** 응답을 종류로 나눈다. 409(좌석 충돌)는 락이 제대로 일한 정상 결과라 실패가 아니다. */
export function classify(status) {
  if (status === 0) return "error"; // 연결 실패, 타임아웃
  if (status >= 200 && status < 300) return "ok";
  if (status === 409) return "conflict";
  if (status === 503) return "unavailable"; // 서버가 "지금은 안 됨"이라고 빠르게 답함
  if (status >= 500) return "server"; // 영문 모를 서버 오류
  if (status >= 400) return "client";
  return "ok";
}
export const BAD = new Set(["error", "unavailable", "server"]);

export function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

/** 시각을 구간 이름으로 바꾼다. marks는 호스트 시계의 epoch ms: { faultStartMs, restoreStartMs, readyMs } */
export function phaseOf(tMs, marks) {
  if (tMs < marks.faultStartMs) return "before";
  if (tMs < marks.restoreStartMs) return "during";
  if (tMs < marks.readyMs) return "recovering";
  return "after";
}
export const PHASES = ["before", "during", "recovering", "after"];
export const PHASE_LABEL = { before: "장애 전", during: "장애 중", recovering: "복구 중(해제~준비)", after: "복구 후" };

/** 점들의 요약: 개수, 종류별 개수, 지연 p50/p95/최대 */
export function summarize(points) {
  const counts = { ok: 0, conflict: 0, client: 0, unavailable: 0, server: 0, error: 0 };
  const latencies = [];
  for (const p of points) {
    counts[classify(p.status)]++;
    latencies.push(p.ms);
  }
  latencies.sort((a, b) => a - b);
  const bad = counts.unavailable + counts.server + counts.error;
  return {
    total: points.length,
    counts,
    bad,
    badRate: points.length ? bad / points.length : 0,
    p50: percentile(latencies, 50),
    p95: percentile(latencies, 95),
    max: latencies.length ? latencies[latencies.length - 1] : null,
  };
}

/** 실패 응답만의 지연. "장애 때 사용자가 오류를 얼마나 빨리 받는가"를 보려는 것이다. */
export function badLatencyP95(points) {
  const sorted = points.filter((p) => BAD.has(classify(p.status))).map((p) => p.ms).sort((a, b) => a - b);
  return percentile(sorted, 95);
}

/** 초 단위 버킷: 구간 [startMs, endMs)를 bucketSec 초씩 나눠 요약한다. */
export function timeline(points, startMs, endMs, bucketSec) {
  const size = bucketSec * 1000;
  const n = Math.max(0, Math.ceil((endMs - startMs) / size));
  const buckets = Array.from({ length: n }, () => []);
  for (const p of points) {
    if (p.t < startMs || p.t >= endMs) continue;
    buckets[Math.floor((p.t - startMs) / size)].push(p);
  }
  return buckets.map((pts, i) => ({ startMs: startMs + i * size, ...summarize(pts) }));
}

/**
 * afterMs 이후 처음으로 "이 시점부터 windowSec 동안 실패율이 threshold 미만"이 되는 시점까지 걸린 초를 돌려준다.
 * 그런 시점이 없으면 null. 창 안에 요청이 minRequests개보다 적으면 판단할 수 없어 건너뛴다.
 */
export function recoverySeconds(points, afterMs, windowSec = 10, threshold = 0.01, minRequests = 5) {
  const sorted = points.filter((p) => p.t >= afterMs).sort((a, b) => a.t - b.t);
  if (sorted.length === 0) return null;
  const lastMs = sorted[sorted.length - 1].t;
  for (let start = afterMs; start + windowSec * 1000 <= lastMs + 1000; start += 1000) {
    const window = sorted.filter((p) => p.t >= start && p.t < start + windowSec * 1000);
    if (window.length < minRequests) continue;
    const bad = window.filter((p) => BAD.has(classify(p.status))).length;
    if (bad / window.length < threshold) return (start - afterMs) / 1000;
  }
  return null;
}

/** ALERTS 시계열 결과에서 알림 이름별로 처음 울린 시각(epoch ms, 호스트 시계)을 뽑는다. */
export function firstFiring(series, vmOffsetMs) {
  const first = {};
  for (const s of series) {
    const name = s.metric.alertname;
    for (const [ts, value] of s.values) {
      if (value === "1") {
        const hostMs = Number(ts) * 1000 - vmOffsetMs;
        if (first[name] === undefined || hostMs < first[name]) first[name] = hostMs;
        break;
      }
    }
  }
  return first;
}

/** 대상별로 울려야 하는 알림. 보고서가 "울렸는가"를 판정하는 기준이다. */
export const EXPECTED_ALERTS = {
  redis: ["RedisDown"],
  mysql: ["MySqlDown"],
  kafka: ["OutboxBacklog"],
  gateway: ["ServiceDown"],
  "auth-service": ["ServiceDown"],
  "event-service": ["ServiceDown", "CircuitOpen"],
  "reservation-service": ["ServiceDown", "CircuitOpen"],
  "order-service": ["ServiceDown"],
  backend: ["ServiceDown"],
};

// ---------- k6 기록 읽기 ----------

/**
 * k6 JSON 한 줄이 "본 부하의 요청 기록"이면 그 시각(epoch ms, k6 시계)을, 아니면 null을 돌려준다.
 * 파일 첫머리의 지표 설명 줄("type":"Metric")과 시험 전 준비 단계(setup_...)의 요청은 부하의 시작이 아니다.
 */
export function loadPointTime(line) {
  if (!line.includes('"http_req_duration"')) return null;
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  if (o.type !== "Point" || o.metric !== "http_req_duration") return null;
  if ((o.data?.tags?.name ?? "").startsWith("setup_")) return null;
  const t = Date.parse(o.data.time);
  return Number.isFinite(t) ? t : null;
}

/** k6 JSON 출력(.gz 가능)에서 요청별 지연 기록만 읽는다. */
export async function readK6Points(path) {
  const raw = createReadStream(path);
  const input = path.endsWith(".gz") ? raw.pipe(createGunzip()) : raw;
  const points = [];
  for await (const line of createInterface({ input, crlfDelay: Infinity })) {
    if (!line.includes('"http_req_duration"')) continue;
    let o;
    try {
      o = JSON.parse(line);
    } catch {
      continue;
    }
    if (o.type !== "Point" || o.metric !== "http_req_duration") continue;
    const name = o.data.tags?.name ?? "";
    if (name.startsWith("setup_")) continue; // 시험 시작 전 로그인은 측정 밖
    points.push({ t: Date.parse(o.data.time), name, status: Number(o.data.tags?.status ?? 0), ms: o.data.value });
  }
  return points;
}

// ---------- 보고서 만들기 ----------

const pct = (x) => `${(x * 100).toFixed(1)}%`;
const ms = (x) => (x === null || x === undefined ? "-" : x >= 1000 ? `${(x / 1000).toFixed(2)}s` : `${Math.round(x)}ms`);
const secs = (x) => (x === null || x === undefined ? "-" : `${x.toFixed(1)}초`);

function summaryRow(label, s) {
  const c = s.counts;
  return `| ${label} | ${s.total} | ${c.ok} | ${c.conflict} | ${c.client} | ${c.unavailable} | ${c.server} | ${c.error} | ${pct(s.badRate)} | ${ms(s.p50)} | ${ms(s.p95)} | ${ms(s.max)} |`;
}
const TABLE_HEAD =
  "| 구분 | 요청 | 성공 | 충돌409 | 기타4xx | 503 | 기타5xx | 연결실패·시간초과 | 실패율 | p50 | p95 | 최대 |\n|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|";

export function buildReport({ meta, chaos, points, alerts, serverMetrics, invariants }) {
  const marks = { faultStartMs: chaos.faultStartMs, restoreStartMs: chaos.restoreStartMs, readyMs: chaos.readyMs };
  const byPhase = Object.fromEntries(PHASES.map((p) => [p, points.filter((pt) => phaseOf(pt.t, marks) === p)]));
  const sums = Object.fromEntries(PHASES.map((p) => [p, summarize(byPhase[p])]));
  const recovery = recoverySeconds(points, chaos.restoreStartMs);
  const duringBadP95 = badLatencyP95(byPhase.during);
  const expected = EXPECTED_ALERTS[chaos.target] ?? [];
  const detect = expected.map((name) => ({ name, afterSec: alerts?.[name] === undefined ? null : (alerts[name] - chaos.faultStartMs) / 1000 }));
  const firstDetect = detect.filter((d) => d.afterSec !== null).map((d) => d.afterSec).sort((a, b) => a - b)[0] ?? null;
  const remaining = invariants?.remaining ?? {};
  const remainingCount = Object.values(remaining).reduce((s, k) => s + k.length, 0);

  // 합격 기준(계획서의 4가지). 기준에 못 미치면 고칠 것을 찾는 출발점이 된다.
  const verdicts = [
    {
      name: "장애 중 오류를 5초 안에 받는다",
      detail: `장애 중 실패 응답의 p95 지연 ${ms(duringBadP95)}, 실패율 ${pct(sums.during.badRate)}`,
      pass: sums.during.bad === 0 ? null : duringBadP95 !== null && duringBadP95 <= 5000,
    },
    {
      name: "장애 후 60초 안에 알림이 울린다",
      detail: expected.length ? detect.map((d) => `${d.name} ${d.afterSec === null ? "울리지 않음" : secs(d.afterSec)}`).join(", ") : "이 대상의 기대 알림 없음",
      pass: expected.length === 0 ? null : firstDetect !== null && firstDetect <= 60,
    },
    {
      name: "장애가 끝나고 60초 안에 오류율이 정상으로 돌아온다",
      detail: `장애 해제 후 ${secs(recovery)} (10초 창에서 실패율 1% 미만)`,
      pass: recovery !== null && recovery <= 60,
    },
    {
      name: "데이터 정합성 위반이 새로 생기지 않는다",
      detail: invariants ? `새 위반 ${remainingCount}건` : "검사 결과 없음",
      pass: invariants ? remainingCount === 0 : null,
    },
  ];
  const mark = (p) => (p === null ? "해당 없음" : p ? "합격" : "**불합격**");

  const lines = [];
  lines.push(`# 장애 시험 보고서: ${meta.label}`, "");
  lines.push(`- 장애: **${chaos.target}** ${chaos.mode} (장애 시작 후 ${((chaos.faultAppliedMs - chaos.faultStartMs) / 1000).toFixed(1)}초에 적용 완료, ${chaos.forSec}초 유지)`);
  lines.push(`- 복구: 해제 후 ${((chaos.readyMs - chaos.restoreStartMs) / 1000).toFixed(1)}초에 요청을 받을 수 있는 상태가 됨`);
  lines.push(`- 부하: ${meta.config.users}명, ${meta.config.durationSec}초, 공연 ${meta.config.event}, 요청 타임아웃 ${meta.config.reqTimeout}`);
  lines.push(`- 시계 보정: Docker VM이 호스트보다 ${meta.vmOffsetMs}ms 앞섬(오차 약 ±1초)`, "");

  lines.push("## 판정", "", "| 기준 | 결과 | 값 |", "|---|---|---|");
  for (const v of verdicts) lines.push(`| ${v.name} | ${mark(v.pass)} | ${v.detail} |`);
  lines.push("");

  lines.push("## 사용자가 겪은 일 (구간별)", "", TABLE_HEAD);
  for (const p of PHASES) lines.push(summaryRow(PHASE_LABEL[p], sums[p]));
  lines.push("", "충돌409는 좌석 락이 정상으로 일한 결과라 실패로 세지 않는다. 실패율 = (503 + 기타5xx + 연결실패·시간초과) / 요청.", "");

  lines.push("## 장애 중 엔드포인트별", "", TABLE_HEAD);
  const names = [...new Set(byPhase.during.map((p) => p.name))].sort();
  for (const n of names) lines.push(summaryRow(n, summarize(byPhase.during.filter((p) => p.name === n))));
  lines.push("");

  const from = chaos.faultStartMs - 30_000;
  const lastPointMs = points.reduce((max, p) => (p.t > max ? p.t : max), chaos.readyMs);
  const to = Math.min(chaos.readyMs + 90_000, lastPointMs + 1000);
  lines.push("## 시간대별 (5초 단위, 0초 = 장애 시작)", "", "| 시각 | 요청 | 성공 | 실패 | 실패율 | p95 |", "|--:|--:|--:|--:|--:|--:|");
  for (const b of timeline(points, from, to, 5)) {
    const rel = Math.round((b.startMs - chaos.faultStartMs) / 1000);
    const tag = b.startMs >= chaos.faultStartMs && b.startMs < chaos.restoreStartMs ? " ◀ 장애" : "";
    lines.push(`| ${rel >= 0 ? "+" : ""}${rel}s${tag} | ${b.total} | ${b.counts.ok} | ${b.bad} | ${pct(b.badRate)} | ${ms(b.p95)} |`);
  }
  lines.push("");

  lines.push("## 알림", "");
  if (expected.length) {
    lines.push("| 알림 | 장애 시작 후 |", "|---|--:|");
    for (const d of detect) lines.push(`| ${d.name} | ${d.afterSec === null ? "울리지 않음" : secs(d.afterSec)} |`);
  }
  if (alerts) {
    const others = Object.entries(alerts).filter(([n]) => !expected.includes(n));
    if (others.length) lines.push("", "그 밖에 울린 알림: " + others.map(([n, t]) => `${n}(${secs((t - chaos.faultStartMs) / 1000)})`).join(", "));
  }
  if (meta.slack) lines.push("", `Slack 전송: 성공 ${meta.slack.after.sent - meta.slack.before.sent}건, 실패 ${meta.slack.after.failed - meta.slack.before.failed}건 (Alertmanager 집계)`);
  lines.push("");

  if (serverMetrics) {
    lines.push("## 서버 쪽 지표 (장애 시작부터 복구 후 90초까지 최대값)", "", "| 지표 | 최대 |", "|---|--:|");
    for (const [label, value] of Object.entries(serverMetrics)) lines.push(`| ${label} | ${value === null ? "-" : Number(value).toFixed(2)} |`);
    lines.push("");
  }

  lines.push("## 데이터 정합성", "");
  if (invariants) {
    lines.push(`시험 전 기준선에 없던 새 위반: **${remainingCount}건** (장애 후 최대 ${invariants.waitedSec.toFixed(0)}초 기다리며 확인)`);
    for (const [id, keys] of Object.entries(remaining)) if (keys.length) lines.push(`- ${id}: ${keys.length}건 (예: ${keys.slice(0, 5).join(", ")})`);
    const first = Object.values(invariants.firstSeen ?? {}).reduce((s, k) => s + k.length, 0);
    if (first > 0 && remainingCount === 0) lines.push(`- 검사 첫 시점에는 ${first}건이 있었지만 서비스가 스스로 정리했습니다.`);
  } else {
    lines.push("검사 결과 없음");
  }
  lines.push("", "## 한계", "", "- 한 PC에서 docker 명령으로 흉내 낸 장애다. 실제 네트워크 단절이나 부분 장애와는 다르다.", "- 서버와 부하 생성기가 같은 PC의 자원을 나눠 쓴다. 절대값보다 구간끼리의 비교로 읽어야 한다.", `- 요청 타임아웃을 ${meta.config.reqTimeout}으로 끊었다. 실제 브라우저는 더 오래 기다릴 수 있다.`, "");
  return lines.join("\n");
}

/**
 * 장애 없이 같은 부하만 돌린 기준선 보고서. 이후 장애 시험의 "정상일 때는 이 정도"를 정하는 값이다.
 * 처음 warmupSec초(JIT, 커넥션 풀, 캐시가 덜 데워진 구간)는 뺀 정상 상태 수치를 따로 낸다.
 */
export function buildBaselineReport({ meta, points, alerts, serverMetrics, invariants, loadStartMs, warmupSec = 30 }) {
  const steady = points.filter((p) => p.t >= loadStartMs + warmupSec * 1000);
  const all = summarize(points);
  const st = summarize(steady);
  const alertNames = Object.keys(alerts ?? {});
  const remaining = invariants?.remaining ?? {};
  const remainingCount = Object.values(remaining).reduce((s, k) => s + k.length, 0);
  // 기준선으로 못 쓰는 이유를 모두 모은다. "쓸 수 없음"만 적으면 무엇을 고쳐야 하는지 알 수 없다.
  const reasons = [];
  if (st.total === 0) reasons.push("정상 상태 구간에 요청이 없음");
  if (st.badRate >= 0.01) reasons.push(`정상 상태 실패율 ${pct(st.badRate)}`);
  if (alertNames.length > 0) reasons.push(`장애 없이 알림이 울림: ${alertNames.join(", ")}`);
  if (remainingCount > 0) reasons.push(`새 정합성 위반 ${remainingCount}건`);
  const usable = reasons.length === 0;

  const lines = [];
  lines.push(`# 정상 상태 기준선: ${meta.label}`, "");
  lines.push(`- 부하: ${meta.config.users}명, ${meta.config.durationSec}초, 공연 ${meta.config.event}, 요청 타임아웃 ${meta.config.reqTimeout}. 장애는 넣지 않았다.`);
  lines.push(`- 워밍업: 처음 ${warmupSec}초는 정상 상태 수치에서 뺐다.`);
  lines.push(`- 시계 보정: Docker VM이 호스트보다 ${meta.vmOffsetMs}ms 앞섬(오차 약 ±1초)`, "");

  lines.push("## 판정", "", `기준선으로 쓸 수 있는가: ${usable ? "**쓸 수 있음**" : "**쓸 수 없음**"} (조건: 정상 상태 실패율 1% 미만, 울린 알림 없음, 새 정합성 위반 없음)`);
  if (!usable) lines.push("", "쓸 수 없는 이유:", ...reasons.map((r) => `- ${r}`));
  lines.push("");
  lines.push("| 확인 | 값 |", "|---|---|");
  lines.push(`| 정상 상태 실패율 | ${pct(st.badRate)} (${st.bad}/${st.total}) |`);
  lines.push(`| 시험 중 울린 알림 | ${alertNames.length ? alertNames.join(", ") : "없음"} |`);
  lines.push(`| 새 정합성 위반 | ${invariants ? `${remainingCount}건` : "검사 결과 없음"} |`, "");

  lines.push("## 구간별", "", TABLE_HEAD, summaryRow("전체", all), summaryRow(`정상 상태(워밍업 ${warmupSec}초 제외)`, st), "");
  lines.push("충돌409는 좌석 락이 정상으로 일한 결과라 실패로 세지 않는다. 실패율 = (503 + 기타5xx + 연결실패·시간초과) / 요청.", "");

  lines.push("## 엔드포인트별 (정상 상태)", "", TABLE_HEAD);
  for (const n of [...new Set(steady.map((p) => p.name))].sort()) lines.push(summaryRow(n, summarize(steady.filter((p) => p.name === n))));
  lines.push("");

  const lastMs = points.reduce((max, p) => (p.t > max ? p.t : max), loadStartMs);
  lines.push("## 시간대별 (30초 단위)", "", "| 시각 | 요청 | 성공 | 실패 | 실패율 | p95 |", "|--:|--:|--:|--:|--:|--:|");
  for (const b of timeline(points, loadStartMs, lastMs + 1000, 30)) {
    lines.push(`| +${Math.round((b.startMs - loadStartMs) / 1000)}s | ${b.total} | ${b.counts.ok} | ${b.bad} | ${pct(b.badRate)} | ${ms(b.p95)} |`);
  }
  lines.push("");

  if (serverMetrics) {
    lines.push("## 서버 쪽 지표 (시험 전체 최대값)", "", "| 지표 | 최대 |", "|---|--:|");
    for (const [label, value] of Object.entries(serverMetrics)) lines.push(`| ${label} | ${value === null ? "-" : Number(value).toFixed(2)} |`);
    lines.push("");
  }

  lines.push("## 데이터 정합성", "");
  if (invariants) {
    lines.push(`시험 전 기준선에 없던 새 위반: **${remainingCount}건**`);
    for (const [id, keys] of Object.entries(remaining)) if (keys.length) lines.push(`- ${id}: ${keys.length}건 (예: ${keys.slice(0, 5).join(", ")})`);
  } else {
    lines.push("검사 결과 없음");
  }
  lines.push("", "## 한계", "", "- 한 PC에서 서버와 부하 생성기가 자원을 나눠 쓴 값이라 절대값이 아니라 장애 시험과의 비교용이다.", `- 요청 타임아웃을 ${meta.config.reqTimeout}으로 끊었다.`, "");
  return lines.join("\n");
}

/** 장애 시작부터 복구 후 90초 사이의 서버 쪽 지표 최대값 */
export async function collectServerMetrics(chaos, vmOffsetMs) {
  return collectServerMetricsBetween(chaos.faultStartMs - 5000, chaos.readyMs + 90_000, vmOffsetMs);
}

/** 호스트 시계 기준 [startMs, endMs] 사이의 서버 쪽 지표 최대값 */
export async function collectServerMetricsBetween(startMs, endMs, vmOffsetMs) {
  const startSec = Math.floor((startMs + vmOffsetMs) / 1000);
  const endSec = Math.ceil((endMs + vmOffsetMs) / 1000);
  const queries = {
    "서비스 5xx 응답률(초당, 합계)": 'sum(rate(http_server_requests_seconds_count{status=~"5.."}[30s]))',
    "DB 연결을 기다리는 요청 수": "max(hikaricp_connections_pending)",
    "아웃박스에서 가장 오래 기다린 이벤트(초)": "max(outbox_oldest_unsent_age_seconds)",
    "서킷 열림(1=열림)": 'max(resilience4j_circuitbreaker_state{state="open"})',
  };
  const result = {};
  for (const [label, q] of Object.entries(queries)) {
    try {
      const series = await promRange(q, startSec, endSec, 5);
      const values = series.flatMap((s) => s.values.map((v) => Number(v[1]))).filter((v) => Number.isFinite(v));
      result[label] = values.length ? Math.max(...values) : null;
    } catch {
      result[label] = null;
    }
  }
  return result;
}

/** 시험 구간에서 울린 알림(처음 울린 시각, 호스트 시계) */
export async function collectAlerts(chaos, vmOffsetMs) {
  return collectAlertsBetween(chaos.faultStartMs - 30_000, chaos.readyMs + 120_000, vmOffsetMs);
}

/** 호스트 시계 기준 [startMs, endMs] 사이에 울린 알림 */
export async function collectAlertsBetween(startMs, endMs, vmOffsetMs) {
  const startSec = Math.floor((startMs + vmOffsetMs) / 1000);
  const endSec = Math.ceil((endMs + vmOffsetMs) / 1000);
  const series = await promRange('ALERTS{alertstate="firing"}', startSec, endSec, 5);
  return firstFiring(series, vmOffsetMs);
}

async function main() {
  const { values } = parseArgs({ options: { dir: { type: "string" }, "no-prom": { type: "boolean", default: false } } });
  if (!values.dir) {
    console.error("사용법: node scripts/chaos/report.mjs --dir loadtest/results/<id> [--no-prom]");
    process.exit(2);
  }
  const read = (f) => JSON.parse(readFileSync(join(values.dir, f), "utf8"));
  const meta = read("meta.json");
  const rawPoints = await readK6Points(join(values.dir, "k6.json.gz"));
  // k6의 시각은 Docker VM 시계라서 호스트 시계로 맞춘다.
  const points = rawPoints.map((p) => ({ ...p, t: p.t - meta.vmOffsetMs }));
  let md;
  if (meta.mode === "baseline") {
    const endMs = points.reduce((max, p) => (p.t > max ? p.t : max), meta.startedAtMs);
    const alerts = values["no-prom"] ? null : await collectAlertsBetween(meta.startedAtMs, endMs + 60_000, meta.vmOffsetMs).catch(() => null);
    const serverMetrics = values["no-prom"] ? null : await collectServerMetricsBetween(meta.startedAtMs, endMs, meta.vmOffsetMs).catch(() => null);
    md = buildBaselineReport({ meta, points, alerts, serverMetrics, invariants: meta.invariants, loadStartMs: meta.startedAtMs });
  } else {
    const chaos = read("chaos.json");
    const alerts = values["no-prom"] ? null : await collectAlerts(chaos, meta.vmOffsetMs).catch(() => null);
    const serverMetrics = values["no-prom"] ? null : await collectServerMetrics(chaos, meta.vmOffsetMs).catch(() => null);
    md = buildReport({ meta, chaos, points, alerts, serverMetrics, invariants: meta.invariants });
  }
  writeFileSync(join(values.dir, "report.md"), md);
  console.log(`보고서: ${join(values.dir, "report.md")}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
