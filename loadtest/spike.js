import http from "k6/http";
import { sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// 티켓팅 오픈 순간: N명이 동시에 한꺼번에 몰려서 각자 한 번씩 "줄 서기 -> 입장 기다리기 -> 좌석 하나 예약"만 하고 끝낸다.
// reserve_flow.js는 사용자를 서서히 늘리면서 예약/취소를 계속 반복하는 용량 테스트이고, 이 스크립트는 오픈 직후의 폭주를 본다.
//
//   준비: 서로 다른 계정 N개(spike1~spikeN@example.com)가 필요하다 -> seed_spike_users.sql
//   실행: docker run --rm --network ticketing-service_default -e BASE_URL=http://nginx/api \
//           -e USERS=1000 -e EVENT_ID=1 -v "$(pwd)/loadtest:/scripts" grafana/k6 run /scripts/spike.js
//
// 로그인은 오픈 전에 미리 해 두는 것이 현실적이라 setup()에서 먼저 하고(측정 밖), 본 측정은 줄 서기부터 시작한다.
// 입장을 기다리는 동안은 2초마다 상태를 묻는다(k6는 SSE 스트림을 읽지 못하므로, 스트림을 못 쓸 때의 대체 경로와 같다).
// 게이트웨이 레이트 리미팅과 캡차는 끄고(RATE_LIMIT_ENABLED=false CAPTCHA_ENABLED=false) 돌린다.

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const USERS = Number(__ENV.USERS || 1000);
const ACCOUNT_PREFIX = __ENV.ACCOUNT_PREFIX || "spike";
const MAX_WAIT_S = Number(__ENV.MAX_WAIT_S || 300); // 줄에서 이 시간 넘게 기다리면 포기한 사람으로 센다
const POLL_S = Number(__ENV.POLL_S || 2);
const CANCEL = __ENV.CANCEL !== "false"; // 끝나고 예약을 취소해서 좌석을 돌려놓는다(다시 돌릴 수 있게). CANCEL=false면 그대로 둔다

const queueEnter = new Trend("queue_enter_ms", true); // 줄 서기 요청의 응답 시간
const queueWait = new Trend("queue_wait_s"); // 줄을 선 시각부터 입장까지(초)
const reserveTime = new Trend("reserve_ms", true); // 예약 요청의 응답 시간
const journey = new Trend("journey_s"); // 줄을 선 시각부터 예약 결과까지(초)
const enterFailed = new Counter("enter_failed"); // 줄 서기 자체가 실패
const gaveUp = new Counter("gave_up_waiting"); // 입장을 못 하고 기다리다 포기
const reserved = new Counter("reserved"); // 좌석을 잡음
const soldOut = new Counter("seat_taken_gave_up"); // 좌석 충돌이 반복돼서 못 잡음(정상 결과)
const reserveError = new Counter("reserve_error"); // 충돌(409)이 아닌 예약 실패

export const options = {
  setupTimeout: "10m",
  scenarios: {
    open: {
      // 모두가 같은 순간에 시작해서 각자 한 번만 한다
      executor: "per-vu-iterations",
      vus: USERS,
      iterations: 1,
      maxDuration: `${MAX_WAIT_S + 180}s`,
    },
  },
  thresholds: {
    enter_failed: ["count==0"],
    reserve_error: ["count==0"],
    queue_enter_ms: ["p(95)<3000"],
  },
};

// 실패한 요청은 어떤 요청이 어떤 상태로 실패했는지 한 줄씩 남긴다(status 0은 응답을 못 받은 것: 시간 초과나 연결 오류).
// 끝난 뒤 `docker logs <k6 컨테이너> | grep "ERR " | sort | uniq -c`로 센다.
function logFailure(name, res) {
  console.warn(`ERR ${name} status=${res.status} ${res.error_code || ""} ${String(res.body || "").slice(0, 60).replace(/\s+/g, " ")}`);
}

function authHeaders(token, extra) {
  return { headers: Object.assign({ "Content-Type": "application/json", Authorization: `Bearer ${token}` }, extra || {}) };
}

export function setup() {
  const tokens = [];
  for (let from = 1; from <= USERS; from += 50) {
    const requests = [];
    for (let n = from; n < from + 50 && n <= USERS; n++) {
      requests.push([
        "POST",
        `${BASE_URL}/auth/login`,
        JSON.stringify({ email: `${ACCOUNT_PREFIX}${n}@example.com`, password: "password123" }),
        { headers: { "Content-Type": "application/json" }, tags: { name: "setup_login" } },
      ]);
    }
    for (const res of http.batch(requests)) {
      tokens.push(res.status === 200 ? res.json("accessToken") : null);
    }
  }
  const missing = tokens.filter((t) => t === null).length;
  if (missing > 0) {
    throw new Error(`${missing} of ${USERS} accounts could not log in - run seed_spike_users.sql first`);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const t0 = Date.now();

  // 1) 줄 서기
  const entered = http.post(`${BASE_URL}/queue/enter`, JSON.stringify({ eventId: EVENT_ID }), Object.assign({ tags: { name: "queue_enter" } }, authHeaders(token)));
  queueEnter.add(entered.timings.duration);
  if (entered.status !== 200) {
    logFailure("queue_enter", entered);
    enterFailed.add(1);
    return;
  }
  const queueToken = entered.json("queueToken");

  // 2) 입장할 때까지 기다리기
  let passToken = null;
  while (Date.now() - t0 < MAX_WAIT_S * 1000) {
    const status = http.get(`${BASE_URL}/queue/status?queueToken=${queueToken}`, Object.assign({ tags: { name: "queue_status" } }, authHeaders(token)));
    if (status.status === 200 && status.json("status") === "PASSED") {
      passToken = status.json("passToken");
      break;
    }
    if (status.status !== 200) logFailure("queue_status", status);
    sleep(POLL_S * (0.8 + Math.random() * 0.4));
  }
  if (!passToken) {
    gaveUp.add(1);
    return;
  }
  queueWait.add((Date.now() - t0) / 1000);

  // 3) 좌석 하나 잡기: 좌석 목록을 받아 빈 좌석 중 무작위로 고르고, 충돌하면 다른 좌석으로 최대 3번
  let reservationId = null;
  for (let attempt = 0; attempt < 3 && reservationId === null; attempt++) {
    const list = http.get(`${BASE_URL}/events/${EVENT_ID}/seats`, { headers: { "Accept-Encoding": "gzip" }, tags: { name: "seat_list" } });
    if (list.status !== 200) logFailure("seat_list", list);
    const seats = list.status === 200 ? list.json() : [];
    const available = (Array.isArray(seats) ? seats : seats.seats || []).filter((s) => s.status === "AVAILABLE");
    if (available.length === 0) {
      soldOut.add(1);
      break;
    }
    const seat = available[Math.floor(Math.random() * available.length)];
    const res = http.post(`${BASE_URL}/reservations`, JSON.stringify({ seatId: seat.id }), Object.assign({ tags: { name: "reserve" } }, authHeaders(token, { "X-Pass-Token": passToken })));
    reserveTime.add(res.timings.duration);
    if (res.status === 201) {
      reservationId = res.json("reservationId");
      reserved.add(1);
    } else if (res.status !== 409) {
      logFailure("reserve", res);
      reserveError.add(1);
      break;
    } else if (attempt === 2) {
      soldOut.add(1);
    }
  }
  journey.add((Date.now() - t0) / 1000);

  if (CANCEL && reservationId !== null) {
    http.del(`${BASE_URL}/reservations/${reservationId}`, null, Object.assign({ tags: { name: "cancel" } }, authHeaders(token)));
  }
}
