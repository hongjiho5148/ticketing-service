import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// 로그인 -> 대기열 진입/통과 -> 좌석 예약 -> (성공하면) 예약 취소, 반복
// 대기열 병목을 배제하고 예약 파이프라인 자체의 처리량/지연시간을 측정하기 위해
// QUEUE_ACTIVE_CAPACITY/QUEUE_ADMISSION_INTERVAL_MS를 테스트 동안만 크게/짧게 올려둔 상태로 돌립니다.

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const USER_COUNT = Number(__ENV.USER_COUNT || 50);

const oversellCounter = new Counter("oversell_conflicts"); // 409 SEAT_ALREADY_RESERVED - 정상
const reserveDuration = new Trend("reserve_duration_ms");
const queueDuration = new Trend("queue_wait_duration_ms");

export const options = {
  scenarios: {
    ramp: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s", target: 1000 },
        { duration: "60s", target: 2500 },
        { duration: "150s", target: 5000 },
        { duration: "30s", target: 0 },
      ],
      gracefulRampDown: "20s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"], // 5xx/네트워크 실패 기준, 409(정상 충돌)는 별도 카운트라 포함 안 됨
    reserve_duration_ms: ["p(95)<1500"],
  },
};

const tokenByVU = {};

function loginToken() {
  const vu = String(__VU);
  if (tokenByVU[vu]) return tokenByVU[vu];

  const userNo = (__VU % USER_COUNT) + 1;
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: `loadtest${userNo}@example.com`, password: "password123" }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "login" } },
  );
  check(res, { "로그인 200": (r) => r.status === 200 });
  const token = res.json("accessToken");
  tokenByVU[vu] = token;
  return token;
}

function authHeaders(token, extra) {
  return { headers: Object.assign({ "Content-Type": "application/json", Authorization: `Bearer ${token}` }, extra || {}) };
}

function enterAndPassQueue(token) {
  const start = Date.now();
  const enterRes = http.post(
    `${BASE_URL}/queue/enter`,
    JSON.stringify({ eventId: EVENT_ID }),
    Object.assign({ tags: { name: "queue_enter" } }, authHeaders(token)),
  );
  check(enterRes, { "대기열 진입 200": (r) => r.status === 200 });
  const queueToken = enterRes.json("queueToken");
  if (!queueToken) return null;

  for (let i = 0; i < 15; i++) {
    const statusRes = http.get(`${BASE_URL}/queue/status?queueToken=${queueToken}`, Object.assign({ tags: { name: "queue_status" } }, authHeaders(token)));
    if (statusRes.status === 200 && statusRes.json("status") === "PASSED") {
      queueDuration.add(Date.now() - start);
      return statusRes.json("passToken");
    }
    sleep(0.3);
  }
  return null;
}

function pickRandomSeatId(token) {
  const res = http.get(
    `${BASE_URL}/events/${EVENT_ID}/seats`,
    Object.assign({ tags: { name: "list_seats" } }, authHeaders(token)),
  );
  if (res.status !== 200) return null;
  const seats = res.json().filter((s) => s.status === "AVAILABLE");
  if (seats.length === 0) return null;
  return seats[Math.floor(Math.random() * seats.length)].id;
}

export default function () {
  const token = loginToken();
  if (!token) return;

  const passToken = enterAndPassQueue(token);
  if (!passToken) return;

  const seatId = pickRandomSeatId(token);
  if (!seatId) return;

  const start = Date.now();
  const reserveRes = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ seatId }),
    Object.assign({ tags: { name: "reserve" } }, authHeaders(token, { "X-Pass-Token": passToken })),
  );
  reserveDuration.add(Date.now() - start);

  if (reserveRes.status === 201) {
    const reservationId = reserveRes.json("reservationId");
    check(reserveRes, { "예약 201": (r) => r.status === 201 });
    // 좌석 풀을 계속 회전시키기 위해 바로 취소해서 반환
    http.del(`${BASE_URL}/reservations/${reservationId}`, null, Object.assign({ tags: { name: "cancel" } }, authHeaders(token)));
  } else if (reserveRes.status === 409) {
    oversellCounter.add(1); // 락이 정상적으로 동시 요청을 막은 것 - 실패가 아니라 기대되는 결과
  } else {
    check(reserveRes, { "예약 실패는 409만 허용": (r) => r.status === 409 });
  }

  sleep(0.2);
}
