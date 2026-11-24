// 장애 시험 도구의 계산 부분 검사. 실행: node --test scripts/chaos
// docker나 Prometheus 없이 돈다. 도구가 틀린 숫자를 내면 시험 결론이 통째로 틀리므로 계산은 미리 검사해 둔다.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import {
  BAD,
  badLatencyP95,
  buildBaselineReport,
  buildReport,
  classify,
  firstFiring,
  loadPointTime,
  percentile,
  phaseOf,
  readK6Points,
  recoverySeconds,
  summarize,
  timeline,
} from "./report.mjs";
import { countViolations, newViolations } from "./invariants.mjs";

const pt = (t, status, msValue = 100, name = "reserve") => ({ t, status, ms: msValue, name });

test("응답 분류: 409는 실패가 아니고 503은 따로 센다", () => {
  assert.equal(classify(201), "ok");
  assert.equal(classify(409), "conflict");
  assert.equal(classify(404), "client");
  assert.equal(classify(503), "unavailable");
  assert.equal(classify(500), "server");
  assert.equal(classify(0), "error");
  assert.ok(!BAD.has("conflict"));
  assert.ok(BAD.has("unavailable") && BAD.has("server") && BAD.has("error"));
});

test("백분위: 정렬된 값에서 올림 순위로 고른다", () => {
  const v = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.equal(percentile(v, 50), 50);
  assert.equal(percentile(v, 95), 95);
  assert.equal(percentile([], 95), null);
  assert.equal(percentile([7], 95), 7);
});

test("구간 나누기: 경계 시각은 다음 구간에 속한다", () => {
  const marks = { faultStartMs: 1000, restoreStartMs: 2000, readyMs: 3000 };
  assert.equal(phaseOf(999, marks), "before");
  assert.equal(phaseOf(1000, marks), "during");
  assert.equal(phaseOf(1999, marks), "during");
  assert.equal(phaseOf(2000, marks), "recovering");
  assert.equal(phaseOf(3000, marks), "after");
});

test("요약: 실패율에 409는 들어가지 않는다", () => {
  const s = summarize([pt(0, 201), pt(0, 409), pt(0, 503), pt(0, 0), pt(0, 500)]);
  assert.equal(s.total, 5);
  assert.equal(s.bad, 3);
  assert.equal(s.badRate, 0.6);
});

test("실패 응답의 지연만 따로 잰다", () => {
  const points = [pt(0, 201, 10), pt(0, 503, 40), pt(0, 503, 60), pt(0, 0, 20000)];
  assert.equal(badLatencyP95(points), 20000);
  assert.equal(badLatencyP95([pt(0, 201, 10)]), null);
});

test("시간대별 버킷: 범위 밖 점은 버리고 5초씩 나눈다", () => {
  const points = [pt(0, 201), pt(4999, 201), pt(5000, 503), pt(9999, 503), pt(10000, 201), pt(-1, 201)];
  const b = timeline(points, 0, 10000, 5);
  assert.equal(b.length, 2);
  assert.equal(b[0].total, 2);
  assert.equal(b[1].total, 2);
  assert.equal(b[1].bad, 2);
});

test("복구 시간: 실패가 멈추고 10초 동안 깨끗해진 시점까지", () => {
  // 장애 해제 시각 0. 0~14초는 실패, 15초부터 정상. 초당 요청 2개.
  const points = [];
  for (let s = 0; s < 40; s++) for (let k = 0; k < 2; k++) points.push(pt(s * 1000 + k * 100, s < 15 ? 503 : 201));
  assert.equal(recoverySeconds(points, 0), 15);
});

test("복구 시간: 끝까지 실패하면 null, 처음부터 정상이면 0", () => {
  const bad = Array.from({ length: 60 }, (_, i) => pt(i * 500, 503));
  assert.equal(recoverySeconds(bad, 0), null);
  const good = Array.from({ length: 60 }, (_, i) => pt(i * 500, 201));
  assert.equal(recoverySeconds(good, 0), 0);
});

test("복구 시간: 요청이 거의 없는 창은 판단하지 않는다", () => {
  const sparse = [pt(0, 201), pt(30000, 201), pt(31000, 201), pt(32000, 201), pt(33000, 201), pt(34000, 201), pt(35000, 201)];
  const result = recoverySeconds(sparse, 0, 10, 0.01, 5);
  assert.ok(result === null || result >= 25, `요청이 적은 앞부분을 정상 복귀로 세면 안 된다: ${result}`);
});

test("알림 시각: 처음 1이 된 시각을 호스트 시계로 바꾼다", () => {
  const series = [
    { metric: { alertname: "RedisDown" }, values: [[100, "0"], [105, "1"], [110, "1"]] },
    { metric: { alertname: "RedisDown", extra: "x" }, values: [[102, "1"]] },
    { metric: { alertname: "ServerErrorRate" }, values: [[100, "0"]] },
  ];
  const first = firstFiring(series, 2000);
  assert.equal(first.RedisDown, 102 * 1000 - 2000);
  assert.equal(first.ServerErrorRate, undefined);
});

test("정합성: 기준선에 있던 위반은 새 위반이 아니다", () => {
  const before = { A: ["1", "2"], B: [] };
  const after = { A: ["1", "2", "3"], B: ["9"], C: ["5"] };
  const fresh = newViolations(before, after);
  assert.deepEqual(fresh, { A: ["3"], B: ["9"], C: ["5"] });
  assert.equal(countViolations(fresh), 3);
  assert.equal(countViolations(newViolations(after, after)), 0);
});

test("부하 시작 시각: 실제 k6 출력의 지표 설명 줄과 준비 단계 요청은 건너뛴다", () => {
  // 아래 줄들은 실제 k6(grafana/k6) JSON 출력에서 가져온 모양이다.
  const metricLine = '{"type":"Metric","data":{"name":"http_req_duration","type":"trend","contains":"time","thresholds":[],"submetrics":[]},"metric":"http_req_duration"}';
  const setupLogin = '{"metric":"http_req_duration","type":"Point","data":{"time":"2026-11-16T08:18:10.232339973Z","value":1140.9,"tags":{"method":"POST","name":"setup_login","status":"200"}}}';
  const setupCheck = '{"metric":"http_req_duration","type":"Point","data":{"time":"2026-11-16T08:18:09.086359359Z","value":245.2,"tags":{"method":"GET","name":"setup_check","status":"200"}}}';
  const main = '{"metric":"http_req_duration","type":"Point","data":{"time":"2026-11-16T08:18:15.500000000Z","value":30,"tags":{"method":"GET","name":"event_detail","status":"200"}}}';
  assert.equal(loadPointTime(metricLine), null); // 이 줄을 요청으로 읽어 시각이 NaN이 됐던 것이 첫 실행의 실패 원인
  assert.equal(loadPointTime(setupLogin), null);
  assert.equal(loadPointTime(setupCheck), null);
  assert.equal(loadPointTime("깨진 줄"), null);
  assert.equal(loadPointTime(main), Date.parse("2026-11-16T08:18:15.500Z"));
});

test("k6 기록 읽기: 요청 지연만 읽고 setup 로그인은 뺀다", async () => {
  const line = (name, status, value, time) =>
    JSON.stringify({ type: "Point", metric: "http_req_duration", data: { time, value, tags: { name, status: String(status) } } });
  const other = JSON.stringify({ type: "Point", metric: "http_reqs", data: { time: "2026-11-15T10:00:00+09:00", value: 1, tags: {} } });
  const text = [
    line("reserve", 201, 12.5, "2026-11-15T10:00:00.123456789+09:00"),
    line("setup_login", 200, 80, "2026-11-15T09:59:00.000000000+09:00"),
    other,
    "깨진 줄",
    line("queue_enter", 0, 20000, "2026-11-15T10:00:01.500000000+09:00"),
  ].join("\n");
  const dir = mkdtempSync(join(tmpdir(), "chaos-test-"));
  const file = join(dir, "k6.json.gz");
  writeFileSync(file, gzipSync(text));
  const points = await readK6Points(file);
  assert.equal(points.length, 2);
  assert.equal(points[0].status, 201);
  assert.equal(points[1].status, 0);
  assert.equal(points[1].t - points[0].t, 1377); // 소수 초 아래 나노초를 밀리초로 읽는다(버림)
});

test("기준선 보고서: 워밍업 구간의 실패는 정상 상태 수치에서 빠진다", () => {
  const start = 5_000_000;
  const meta = { label: "baseline", vmOffsetMs: 0, config: { users: 100, durationSec: 120, event: 1, reqTimeout: "20s" } };
  const points = [];
  for (let s = 0; s < 120; s++) {
    // 처음 20초는 느리고 실패가 섞이고, 이후는 정상
    const warm = s < 20;
    for (const k of [0, 300]) points.push(pt(start + s * 1000 + k, warm ? 503 : 201, warm ? 900 : 60));
  }
  const clean = { remaining: {}, firstSeen: {}, waitedSec: 0 };
  const md = buildBaselineReport({ meta, points, alerts: {}, serverMetrics: null, invariants: clean, loadStartMs: start, warmupSec: 30 });
  assert.match(md, /기준선으로 쓸 수 있는가: \*\*쓸 수 있음\*\*/);
  assert.match(md, /정상 상태 실패율 \| 0\.0% \(0\//); // 워밍업 20초의 실패 40건은 뺀다
  assert.match(md, /\| 전체 \| 240 \|/);

  // 정상 상태에서도 실패가 1% 이상이면, 알림이 울렸으면, 새 위반이 있으면 쓸 수 없다
  const noisy = points.map((p) => (p.t >= start + 60_000 && p.t < start + 70_000 ? { ...p, status: 503 } : p));
  assert.match(buildBaselineReport({ meta, points: noisy, alerts: {}, serverMetrics: null, invariants: clean, loadStartMs: start }), /쓸 수 없음[\s\S]*정상 상태 실패율/);
  // 이유에 울린 알림의 이름이 들어가야 무엇을 고칠지 알 수 있다
  assert.match(buildBaselineReport({ meta, points, alerts: { ServerErrorRate: start }, serverMetrics: null, invariants: clean, loadStartMs: start }), /쓸 수 없음[\s\S]*알림이 울림: ServerErrorRate/);
  assert.match(buildBaselineReport({ meta, points, alerts: {}, serverMetrics: null, invariants: { remaining: { A: ["1"] }, firstSeen: {}, waitedSec: 0 }, loadStartMs: start }), /쓸 수 없음/);
});

test("보고서: 복구가 늦거나 오류 응답이 느리면 불합격으로 판정한다", () => {
  const base = 1_000_000;
  const chaos = {
    target: "mysql", mode: "pause", forSec: 30,
    faultStartMs: base, faultAppliedMs: base + 1000, restoreStartMs: base + 31000, readyMs: base + 34000,
  };
  const meta = { label: "시험", vmOffsetMs: 0, config: { users: 100, durationSec: 240, event: 1, reqTimeout: "20s" } };
  const noInvariants = { remaining: {}, firstSeen: {}, waitedSec: 0 };

  // 장애 해제 뒤 90초가 지나도 실패가 이어지고, 실패 응답은 20초 만에야 온다 -> 둘 다 불합격이어야 한다.
  const slowAndStuck = [];
  for (let s = -10; s < 150; s++) {
    for (const k of [0, 400]) slowAndStuck.push(pt(base + s * 1000 + k, s >= 0 ? 0 : 201, s >= 0 ? 20000 : 80));
  }
  const report = buildReport({ meta, chaos, points: slowAndStuck, alerts: { MySqlDown: base + 20_000 }, serverMetrics: null, invariants: noInvariants });
  assert.match(report, /오류를 5초 안에 받는다 \| \*\*불합격\*\*/);
  assert.match(report, /오류율이 정상으로 돌아온다 \| \*\*불합격\*\*/);

  // 복구가 정확히 60초 걸리면 합격, 61초면 불합격(경계)
  const withRecovery = (sec) => {
    const pts = [];
    for (let s = -10; s < 200; s++) {
      const failing = s >= 0 && s < 31 + sec; // 해제는 31초. 해제 후 sec초까지 실패
      for (const k of [0, 400]) pts.push(pt(base + s * 1000 + k, failing ? 503 : 201, 40));
    }
    return buildReport({ meta, chaos, points: pts, alerts: { MySqlDown: base + 20_000 }, serverMetrics: null, invariants: noInvariants });
  };
  assert.match(withRecovery(60), /오류율이 정상으로 돌아온다 \| 합격/);
  assert.match(withRecovery(61), /오류율이 정상으로 돌아온다 \| \*\*불합격\*\*/);
});

test("보고서: 합격과 불합격을 기준대로 판정한다", () => {
  const base = 1_000_000;
  const chaos = {
    target: "redis", mode: "stop", forSec: 30,
    faultStartMs: base, faultAppliedMs: base + 2000, restoreStartMs: base + 32000, readyMs: base + 35000,
  };
  const meta = { label: "시험", vmOffsetMs: 0, config: { users: 100, durationSec: 240, event: 1, reqTimeout: "20s" } };
  const points = [];
  for (let s = -20; s < 120; s++) {
    const t = base + s * 1000;
    const failing = s >= 0 && s < 50; // 장애 해제(32초) 뒤에도 18초 더 실패
    points.push(pt(t, failing ? 503 : 201, failing ? 30 : 80));
    points.push(pt(t + 400, failing ? 503 : 201, failing ? 30 : 80));
  }
  const ok = buildReport({
    meta, chaos, points,
    alerts: { RedisDown: base + 20_000 },
    serverMetrics: null,
    invariants: { remaining: { A: [] }, firstSeen: {}, waitedSec: 0 },
  });
  assert.match(ok, /장애 중 오류를 5초 안에 받는다 \| 합격/);
  assert.match(ok, /장애 후 60초 안에 알림이 울린다 \| 합격/);
  assert.match(ok, /오류율이 정상으로 돌아온다 \| 합격/);
  assert.match(ok, /정합성 위반이 새로 생기지 않는다 \| 합격/);

  const bad = buildReport({
    meta, chaos, points,
    alerts: {},
    serverMetrics: null,
    invariants: { remaining: { A: ["7"] }, firstSeen: {}, waitedSec: 60 },
  });
  assert.match(bad, /알림이 울린다 \| \*\*불합격\*\*/);
  assert.match(bad, /정합성 위반이 새로 생기지 않는다 \| \*\*불합격\*\*/);
  assert.match(bad, /RedisDown 울리지 않음/);
});
