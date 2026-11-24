// 여러 번의 기준선 시험을 한 표로 비교한다. 같은 조건을 번갈아 여러 번 잰 결과를 나란히 보려는 것이다.
//
//   node scripts/chaos/compare.mjs loadtest/results/<폴더1> loadtest/results/<폴더2> ...
//
// 사용자 쪽 수치(요청 수, p95)는 k6 기록에서, 서버 쪽 수치(아웃박스)는 시험 구간의 Prometheus에서 읽는다.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { readK6Points, summarize } from "./report.mjs";
import { promQuery, promRange } from "./lib.mjs";

const WARMUP_SEC = 30;

const maxOf = (series) => {
  const values = series.flatMap((s) => s.values.map((v) => Number(v[1]))).filter(Number.isFinite);
  return values.length ? Math.max(...values) : null;
};

async function scalar(expr, atSec) {
  const result = await promQuery(expr, atSec);
  return result.length ? Number(result[0].value[1]) : null;
}

async function inspect(dir) {
  const meta = JSON.parse(readFileSync(join(dir, "meta.json"), "utf8"));
  const startMs = meta.startedAtMs;
  // 부하 시간(+30초 여유)을 넘는 기록은 버린다. 시험 도중 PC가 잠들었다 깨어나면 걸려 있던 요청이 한참 뒤에 마무리되어
  // 며칠 뒤 시각의 기록이 섞이는데, 그대로 두면 시험 구간이 하루로 계산된다.
  const limitMs = startMs + (meta.config.durationSec + 30) * 1000;
  const points = (await readK6Points(join(dir, "k6.json.gz")))
    .map((p) => ({ ...p, t: p.t - meta.vmOffsetMs }))
    .filter((p) => p.t <= limitMs);
  const endMs = points.reduce((m, p) => (p.t > m ? p.t : m), startMs);
  const loadSec = Math.round((endMs - startMs) / 1000);
  const steady = summarize(points.filter((p) => p.t >= startMs + WARMUP_SEC * 1000));

  const vm = (ms) => Math.floor((ms + meta.vmOffsetMs) / 1000);
  const t0 = vm(startMs);
  const t1 = vm(endMs);
  const range = `${t1 - t0}s`;

  const maxAge = maxOf(await promRange("max(outbox_oldest_unsent_age_seconds)", t0, t1, 5));
  const backlogAtEnd = await scalar("max(outbox_unsent_events)", t1);
  const sent = await scalar(`sum(increase(outbox_events_sent_total[${range}]))`, t1);
  const produced = await scalar(
    `sum(increase(spring_data_repository_invocations_seconds_count{application="reservation-service",repository="OutboxEventRepository",method="save"}[${range}]))`,
    t1,
  );

  // 부하가 끝난 뒤 밀린 이벤트가 모두 나가기까지 걸린 시간(끝난 시점에 이미 0이면 0초)
  let drainSec = null;
  if (backlogAtEnd === 0) {
    drainSec = 0;
  } else {
    const after = await promRange("max(outbox_unsent_events)", t1, t1 + 1800, 5);
    const zero = after.flatMap((s) => s.values).find((v) => Number(v[1]) === 0);
    drainSec = zero ? Number(zero[0]) - t1 : null;
  }

  return {
    label: meta.label,
    requests: steady.total,
    reqPerSec: steady.total / Math.max(1, loadSec - WARMUP_SEC),
    p95: steady.p95,
    failRate: steady.badRate,
    maxAge,
    backlogAtEnd,
    produced,
    sent,
    producedPerSec: produced === null ? null : produced / (t1 - t0),
    sentPerSec: sent === null ? null : sent / (t1 - t0),
    drainSec,
    // 부하가 끝난 뒤 릴레이만 일할 때의 처리 속도. 밀린 수가 적으면(수십 개) 값이 의미 없다.
    drainRate: backlogAtEnd && drainSec ? backlogAtEnd / drainSec : null,
  };
}

const fmt = (v, digits = 0) => (v === null || v === undefined ? "-" : Number(v).toFixed(digits));

const dirs = process.argv.slice(2);
if (dirs.length === 0) {
  console.error("사용법: node scripts/chaos/compare.mjs <시험 폴더> [<시험 폴더> ...]");
  process.exit(2);
}
const rows = [];
for (const dir of dirs) rows.push(await inspect(dir));

console.log("| 시험 | 요청/초 | p95(ms) | 실패율 | 이벤트 생산/초 | Kafka 전송/초 | 부하 끝날 때 밀린 수 | 최대 대기(초) | 다 비우는 데(초) | 비울 때 속도(개/초) |");
console.log("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|");
for (const r of rows) {
  console.log(
    `| ${r.label} | ${fmt(r.reqPerSec)} | ${fmt(r.p95)} | ${(r.failRate * 100).toFixed(2)}% | ${fmt(r.producedPerSec, 1)} | ${fmt(r.sentPerSec, 1)} | ${fmt(r.backlogAtEnd)} | ${fmt(r.maxAge)} | ${fmt(r.drainSec)} | ${fmt(r.drainRate)} |`,
  );
}
