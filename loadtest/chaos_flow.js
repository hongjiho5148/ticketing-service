import http from "k6/http";
import { sleep } from "k6";

// 장애 시험용 부하. 사용자 VUS명이 일정하게 "줄 서기 -> 입장 -> 좌석 하나 예약 -> 취소"를 계속 반복한다.
// 장애를 넣기 전후로 같은 부하가 이어져야 사용자가 겪는 일(오류율, 지연)을 전후로 비교할 수 있어서, reserve_flow.js처럼 사용자를
// 늘려 가지 않고 처음부터 끝까지 같은 수를 유지한다.
//
// 이 스크립트는 직접 돌리지 않고 scripts/chaos/run.mjs가 장애 주입, 정합성 검사, 보고서와 함께 돌린다.
// 장애가 나면 요청이 실패하는 것이 정상이라 임계값(thresholds)은 두지 않는다. 결과 판단은 보고서가 한다.
//
// 요청마다 응답이 오지 않으면 REQ_TIMEOUT 뒤에 실패로 끊는다. 응답이 영영 안 오는 요청이 시험 내내 VU를 붙잡아서
// 전후 비교를 흐리지 않게 하기 위해서다. 실제 브라우저는 더 오래 기다릴 수 있다는 점을 보고서에 적는다.

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || "240s";
const ACCOUNT_PREFIX = __ENV.ACCOUNT_PREFIX || "spike"; // seed_spike_users.sql이 만드는 계정(spike1~spikeN)
const REQ_TIMEOUT = __ENV.REQ_TIMEOUT || "20s";
const QUEUE_POLL_LIMIT = Number(__ENV.QUEUE_POLL_LIMIT || 30); // 입장을 이만큼(0.3초 단위) 기다려도 안 되면 이번 회차는 포기

export const options = {
  scenarios: {
    steady: { executor: "constant-vus", vus: VUS, duration: DURATION, gracefulStop: "30s" },
  },
  // 요청 단위 기록(이름, 상태 코드, 지연)이 보고서의 재료다. 불필요한 시스템 태그는 줄여 파일 크기를 줄인다.
  systemTags: ["status", "method", "name", "error_code", "expected_response"],
};

function json(token, extra) {
  return { headers: Object.assign({ "Content-Type": "application/json", Authorization: `Bearer ${token}` }, extra || {}), timeout: REQ_TIMEOUT };
}

function tagged(name, params) {
  return Object.assign({}, params, { tags: { name } });
}

// 예매는 공연 시작 2시간 전까지만 되므로 마감된 공연으로 돌리면 모든 요청이 410으로 끝난다. 시작 전에 알려 준다.
function assertOnSale() {
  // 이름 태그를 달아 두어야 보고서가 준비 단계의 요청(setup_...)으로 알아보고 뺀다.
  const res = http.get(`${BASE_URL}/events/${EVENT_ID}`, { tags: { name: "setup_check" } });
  if (res.status !== 200 || res.json("status") === "CLOSED") {
    throw new Error(`event ${EVENT_ID} is not on sale (status ${res.status === 200 ? res.json("status") : res.status}) - pass a show that can still be booked as EVENT_ID`);
  }
}

function login(userNo) {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: `${ACCOUNT_PREFIX}${userNo}@example.com`, password: "password123" }),
    tagged("login", { headers: { "Content-Type": "application/json" }, timeout: REQ_TIMEOUT }),
  );
  return res.status === 200 ? res.json("accessToken") : null;
}

// 로그인은 시험 시작 전에 미리 해 두는 것이 현실적이다(오픈 전에 로그인해 두는 사용자들). 측정 밖인 setup()에서 한다.
export function setup() {
  assertOnSale();
  const tokens = [];
  for (let from = 1; from <= VUS; from += 50) {
    const requests = [];
    for (let n = from; n < from + 50 && n <= VUS; n++) {
      requests.push([
        "POST",
        `${BASE_URL}/auth/login`,
        JSON.stringify({ email: `${ACCOUNT_PREFIX}${n}@example.com`, password: "password123" }),
        { headers: { "Content-Type": "application/json" }, tags: { name: "setup_login" } },
      ]);
    }
    for (const res of http.batch(requests)) tokens.push(res.status === 200 ? res.json("accessToken") : null);
  }
  const failed = tokens.filter((t) => !t).length;
  if (failed > 0) {
    throw new Error(`${failed}/${VUS}명이 로그인에 실패했습니다. ${ACCOUNT_PREFIX}1~${ACCOUNT_PREFIX}${VUS} 계정이 있는지(seed_spike_users.sql), 속도 제한과 캡차가 꺼져 있는지 확인하세요.`);
  }
  return { tokens };
}

// VU마다 따로 유지되는 값(모듈 변수는 VU별로 하나씩이다). 토큰이 만료돼 다시 로그인하면 여기에 저장해 다음 회차에도 쓴다.
let vuToken = null;

export default function (data) {
  // VU마다 계정 하나를 쓴다(같은 사용자가 둘이면 줄 자리 규칙 때문에 서로의 자리를 버리게 된다).
  const userNo = __VU;
  let token = vuToken || data.tokens[userNo - 1];
  if (!token) {
    sleep(1);
    return;
  }

  // 공연 조회: 게이트웨이가 event-service로 바로 프록시하는 읽기 경로
  http.get(`${BASE_URL}/events/${EVENT_ID}`, tagged("event_detail", { timeout: REQ_TIMEOUT }));

  const enter = http.post(`${BASE_URL}/queue/enter`, JSON.stringify({ eventId: EVENT_ID }), tagged("queue_enter", json(token)));
  if (enter.status === 401) {
    // 로그인 토큰이 만료됐다. 다시 로그인해서 이어 간다.
    token = login(userNo) || token;
    vuToken = token;
  }
  const queueToken = enter.status === 200 ? enter.json("queueToken") : null;
  if (!queueToken) {
    sleep(1); // 실패한 곳에서 바로 되풀이하면 장애 중에 재시도 폭풍이 되어 장애를 키운다
    return;
  }

  let passToken = null;
  for (let i = 0; i < QUEUE_POLL_LIMIT; i++) {
    const status = http.get(`${BASE_URL}/queue/status?queueToken=${queueToken}`, tagged("queue_status", json(token)));
    if (status.status === 200 && status.json("status") === "PASSED") {
      passToken = status.json("passToken");
      break;
    }
    if (status.status >= 400 && status.status !== 429) break; // 자리가 사라졌거나 서버가 오류를 냈다. 이번 회차는 접는다.
    sleep(0.3);
  }
  if (!passToken) {
    sleep(1);
    return;
  }

  const seats = http.get(`${BASE_URL}/events/${EVENT_ID}/seats`, tagged("list_seats", json(token)));
  if (seats.status !== 200) {
    sleep(1);
    return;
  }
  const available = seats.json().filter((s) => s.status === "AVAILABLE");
  if (available.length === 0) {
    sleep(1);
    return;
  }
  const seatId = available[Math.floor(Math.random() * available.length)].id;

  const reserve = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ seatId }),
    tagged("reserve", json(token, { "X-Pass-Token": passToken })),
  );
  if (reserve.status === 201) {
    // 바로 취소해서 좌석을 돌려놓는다. 좌석이 계속 돌아야 같은 부하를 끝까지 유지할 수 있다.
    http.del(`${BASE_URL}/reservations/${reserve.json("reservationId")}`, null, tagged("cancel", json(token)));
  }
  sleep(0.2);
}
