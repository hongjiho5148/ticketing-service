// 장애 전후로 데이터가 어긋나지 않았는지 검사한다. 읽기만 하고 아무것도 바꾸지 않는다.
//
//   node scripts/chaos/invariants.mjs                       현재 상태를 검사해 출력
//   node scripts/chaos/invariants.mjs --save before.json    현재 위반 목록을 기준선으로 저장
//   node scripts/chaos/invariants.mjs --baseline before.json --settle 120
//                                                           기준선에 없던 위반이 사라질 때까지 최대 120초 기다리며 검사
//
// 이 PC의 DB에는 시험과 무관한 옛 데이터가 남아 있을 수 있어서 "위반 0건"이 아니라 "기준선에 없던 새 위반이 없는가"를 본다.
// 서비스가 비동기로 정리하는 것(홀드 만료 스윕, 결제 대사, 아웃박스)은 장애 직후 잠깐 어긋나 보일 수 있다. 그래서 --settle로
// 일정 시간 기다려도 계속 남아 있는 위반만 진짜 문제로 본다.
import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { docker, mysqlRows, redisCli, sleep } from "./lib.mjs";

// 한 검사는 위반한 항목의 키 목록을 돌려준다. 같은 위반인지 기준선과 비교하려고 행 전체가 아니라 키를 쓴다.
const SQL_CHECKS = [
  {
    id: "SEAT_DOUBLE_RESERVED",
    name: "한 좌석에 살아 있는 예약이 둘 이상",
    sql: `SELECT seat_id FROM ticketing_reservation.reservation
          WHERE status = 'CONFIRMED' OR (status = 'HOLDING' AND hold_expire_at > NOW(6))
          GROUP BY seat_id HAVING COUNT(*) > 1`,
  },
  {
    id: "SEAT_DOUBLE_SOLD",
    name: "한 좌석에 결제 완료 주문이 둘 이상(이중 판매)",
    sql: `SELECT seat_id FROM ticketing_order.orders
          WHERE status IN ('PAID', 'PARTIALLY_REFUNDED')
          GROUP BY seat_id HAVING COUNT(*) > 1`,
  },
  {
    id: "SOLD_WITHOUT_CONFIRMED",
    name: "SOLD 좌석인데 확정된 예약이 없음",
    sql: `SELECT s.id FROM ticketing_event.seat s
          WHERE s.status = 'SOLD' AND NOT EXISTS (
            SELECT 1 FROM ticketing_reservation.reservation r WHERE r.seat_id = s.id AND r.status = 'CONFIRMED')`,
  },
  {
    id: "HOLD_WITHOUT_RESERVATION",
    name: "HOLD 좌석인데 살아 있는 예약이 없음(좌석이 아무도 모르게 묶임)",
    sql: `SELECT s.id FROM ticketing_event.seat s
          WHERE s.status = 'HOLD' AND s.hold_expire_at > NOW(6) AND NOT EXISTS (
            SELECT 1 FROM ticketing_reservation.reservation r
            WHERE r.seat_id = s.id AND r.status = 'HOLDING' AND r.hold_expire_at > NOW(6))`,
  },
  {
    id: "RESERVATION_WITHOUT_HOLD",
    name: "살아 있는 예약인데 좌석이 HOLD가 아님",
    sql: `SELECT r.id FROM ticketing_reservation.reservation r
          JOIN ticketing_event.seat s ON s.id = r.seat_id
          WHERE r.status = 'HOLDING' AND r.hold_expire_at > NOW(6) AND s.status <> 'HOLD'`,
  },
  {
    id: "PAID_WITHOUT_CONFIRMED",
    name: "결제 완료 주문인데 예약이 확정되지 않음(결제만 되고 좌석이 없음)",
    sql: `SELECT o.id FROM ticketing_order.orders o
          LEFT JOIN ticketing_reservation.reservation r ON r.id = o.reservation_id
          WHERE o.status = 'PAID' AND (r.id IS NULL OR r.status <> 'CONFIRMED')`,
  },
  {
    id: "OUTBOX_UNSENT",
    name: "Kafka로 나가지 못한 아웃박스 이벤트",
    sql: `SELECT id FROM ticketing_reservation.outbox_event WHERE sent_at IS NULL`,
  },
];

/** 대기열 입장 인원이 정원을 넘지 않는지. 입장권 만료 시각이 지났지만 아직 정리되지 않은 항목은 세지 않는다. */
async function queueOverCapacity(capacity) {
  const keys = (await redisCli(["--scan", "--pattern", "queue:active:*"])).split("\n").map((s) => s.trim()).filter(Boolean);
  const now = Date.now();
  const violations = [];
  for (const key of keys) {
    const live = Number(await redisCli(["ZCOUNT", key, String(now), "+inf"]));
    if (live > capacity) violations.push(`${key}=${live}`);
  }
  return violations;
}

/** 모든 검사를 실행해 { 검사 id: 위반 키 배열 } 을 돌려준다. */
export async function runChecks({ capacity = 500 } = {}) {
  const result = {};
  for (const check of SQL_CHECKS) {
    result[check.id] = (await mysqlRows(check.sql)).map((row) => row[0]);
  }
  result.QUEUE_OVER_CAPACITY = await queueOverCapacity(capacity);
  return result;
}

export const CHECK_NAMES = {
  ...Object.fromEntries(SQL_CHECKS.map((c) => [c.id, c.name])),
  QUEUE_OVER_CAPACITY: "대기열 입장 인원이 정원 초과",
};

/** 기준선(before)에 없던 위반만 골라낸다. */
export function newViolations(before, after) {
  const result = {};
  for (const [id, keys] of Object.entries(after)) {
    const known = new Set(before?.[id] ?? []);
    result[id] = keys.filter((k) => !known.has(k));
  }
  return result;
}

export const countViolations = (violations) => Object.values(violations).reduce((sum, keys) => sum + keys.length, 0);

/**
 * 기준선에 없던 위반이 없어질 때까지 최대 settleSec초 기다리며 반복 검사한다.
 * 끝까지 남은 위반과, 처음에는 있었지만 정리된 위반(= 서비스가 스스로 복구한 것)을 함께 돌려준다.
 */
export async function checkWithSettle({ baseline, settleSec = 0, capacity = 500, intervalSec = 5, log = () => {} }) {
  const startedAt = Date.now();
  let firstSeen = null;
  for (;;) {
    const current = await runChecks({ capacity });
    const fresh = newViolations(baseline, current);
    if (firstSeen === null) firstSeen = fresh;
    const remaining = countViolations(fresh);
    const waited = (Date.now() - startedAt) / 1000;
    if (remaining === 0 || waited >= settleSec) {
      return { current, remaining: fresh, firstSeen, settledAfterSec: remaining === 0 ? waited : null, waitedSec: waited };
    }
    log(`새 위반 ${remaining}건이 남아 있어 ${intervalSec}초 뒤 다시 확인합니다 (${waited.toFixed(0)}/${settleSec}초)`);
    await sleep(intervalSec * 1000);
  }
}

function print(label, violations) {
  console.log(`\n== ${label}`);
  for (const [id, keys] of Object.entries(violations)) {
    const mark = keys.length === 0 ? "OK  " : "위반";
    const sample = keys.length > 0 ? `  예: ${keys.slice(0, 5).join(", ")}${keys.length > 5 ? " ..." : ""}` : "";
    console.log(`  [${mark}] ${CHECK_NAMES[id] ?? id}: ${keys.length}건${sample}`);
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      save: { type: "string" },
      baseline: { type: "string" },
      settle: { type: "string", default: "0" },
      capacity: { type: "string", default: "500" },
    },
  });
  await docker(["ps", "-q", "--filter", "name=ticketing-mysql"]); // docker가 안 되면 여기서 바로 실패
  const baseline = values.baseline ? JSON.parse(readFileSync(values.baseline, "utf8")) : null;
  const outcome = await checkWithSettle({
    baseline,
    settleSec: Number(values.settle),
    capacity: Number(values.capacity),
    log: (m) => console.log(m),
  });
  print(baseline ? "시험 전에 없던 새 위반" : "현재 전체 위반(기준선 없음)", baseline ? outcome.remaining : outcome.current);
  if (baseline && outcome.settledAfterSec !== null && countViolations(outcome.firstSeen) > 0) {
    console.log(`\n처음에는 새 위반 ${countViolations(outcome.firstSeen)}건이 있었지만 ${outcome.settledAfterSec.toFixed(0)}초 안에 서비스가 스스로 정리했습니다.`);
  }
  if (values.save) {
    writeFileSync(values.save, JSON.stringify(outcome.current, null, 2));
    console.log(`\n기준선을 ${values.save}에 저장했습니다.`);
  }
  process.exit(baseline && countViolations(outcome.remaining) > 0 ? 1 : 0);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(2);
  });
}
