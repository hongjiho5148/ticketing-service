import http from "k6/http";
import { check } from "k6";

// 좌석 목록(GET /events/{id}/seats)만 반복 조회해서, 읽기 경로의 응답 크기와 처리량을 잰다.
// 예약 흐름 테스트(reserve_flow.js)는 매 반복마다 이 목록을 통째로 받기 때문에 수신량의 대부분이 여기서 나온다.
// 게이트웨이의 레이트 리미팅이 켜져 있으면 한 IP에서 429가 나오므로 RATE_LIMIT_ENABLED=false 로 띄운 상태에서 돌린다.

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
// 브라우저는 항상 압축을 허용한다고 보낸다. k6는 아무것도 안 보내면 압축 없이 받으므로, 실제 사용에 가깝게 GZIP=false가 아니면 보낸다.
const HEADERS = __ENV.GZIP === "false" ? {} : { "Accept-Encoding": "gzip" };

export const options = {
  scenarios: {
    read: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 200),
      duration: __ENV.DURATION || "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/events/${EVENT_ID}/seats`, { headers: HEADERS, tags: { name: "list_seats" } });
  check(res, {
    "200": (r) => r.status === 200,
    "목록이 비어 있지 않음": (r) => r.json().length > 0,
  });
}
