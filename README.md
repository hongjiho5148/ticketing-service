# 픽시트 (PickSeat)

콘서트 등 한정 좌석에 다수 사용자가 한꺼번에 몰리는 상황을 가정한 **선착순 이벤트 티켓팅 서비스**입니다.
MSA·트래픽 처리 경험을 쌓기 위한 개인 프로젝트로, 단일 모놀리스로 시작해 **동시성 제어 → 수평 확장 → 5단계
마이크로서비스 분리**까지 직접 겪으며 확장했습니다. 모든 설계 변경은 추측이 아니라 k6 부하테스트로 전후 수치를
비교해 검증했습니다.

> 이 프로젝트가 어떤 과정을 거쳐 지금 구조에 이르렀는지는 [`docs/`](./docs) 폴더의 기획 문서를 참고하세요.

## 아키텍처

```mermaid
flowchart TB
    Client["브라우저 (React SPA)"]
    Nginx["nginx :8080\n정적 업스트림 + keepalive"]
    Gateway["Gateway :8080\nSpring Cloud Gateway\nCORS 단일 소유"]

    Client --> Nginx
    Nginx --> Gateway
    Nginx -. "backend 전용 동적\n로드밸런서 :8081" .-> Backend

    Gateway --> Auth["auth-service\n인증 · OAuth2 · JWT 발급"]
    Gateway --> Event["event-service\n이벤트 · 좌석 · 분산락"]
    Gateway --> Reservation["reservation-service\n예약 · 자가치유 스윕"]
    Gateway --> Order["order-service\n주문 · PortOne 결제"]
    Gateway -. "/api/queue/**" .-> Backend["backend\n대기열(Queue)"]

    Order --> Event
    Order --> Reservation
    Reservation --> Event
    Reservation -. Kafka .-> Backend

    Auth --> MySQL[("MySQL\n서비스별 스키마 분리")]
    Event --> MySQL
    Event --> Redis[("Redis\n분산락 · 캐시")]
    Reservation --> MySQL
    Order --> MySQL
    Backend --> MySQL
    Backend --> Redis
```

**입구는 nginx 하나뿐입니다.** nginx가 Gateway로 가는 경로는 고정 IP + 커넥션 풀링(`keepalive`)으로 연결하고,
수평 확장되는 `backend`로 가는 경로만 별도 포트(8081)에서 동적 DNS resolver로 매 요청마다 살아있는 레플리카를
다시 찾습니다 — 이렇게 나눈 이유는 [부하테스트 결과](#부하테스트로-검증한-것들)에 있습니다. CORS는 Gateway
한 곳에서만 처리합니다(개별 서비스가 각자 CORS를 설정하면 헤더가 중복으로 붙어 브라우저가 응답을 거부합니다).

## 서비스 구성

| 서비스 | 역할 | 비고 |
|---|---|---|
| `gateway` | 단일 진입점, 라우팅, CORS | Spring Cloud Gateway (함수형 `RouterFunction` 기반) |
| `auth-service` | 회원가입/로그인, 이메일 인증, OAuth2(Google/Kakao), JWT 발급 | JWT를 **발급**하는 유일한 서비스 |
| `event-service` | 이벤트/좌석 조회, 좌석 홀드 | Redis(Redisson) 분산락으로 동시 홀드 방지 |
| `reservation-service` | 좌석 예약, 예약 확정/취소 | 홀드 만료를 스스로 정리하는 자가치유 스윕 보유 |
| `order-service` | 주문 생성, PortOne 결제/환불 | 결제 실패 시 예약 자동 취소(saga) |
| `backend` | 대기열(Queue) 입장/상태 조회 | 3개 레플리카로 수평 확장되는 유일한 서비스 |
| `nginx` | 외부 진입점, 내부 로드밸런서 | `backend` 전용 동적 로드밸런싱 포트(8081) 별도 운영 |
| `mysql` / `redis` / `kafka` | 데이터 저장소 · 메시징 | 서비스마다 스키마(`ticketing_auth`, `ticketing_event` 등) 분리 |

각 서비스는 자신의 DB 스키마만 소유하며, 서비스 간 참조는 전부 `Long` id + 전용 REST 클라이언트로 이뤄집니다.
JWT는 auth-service만 발급하고, 나머지 서비스는 같은 시크릿으로 검증만 합니다.

## 핵심 기능

**인증**
- 이메일 회원가입(비밀번호 대/소문자·숫자·특수문자 포함 정책 + 실시간 강도 표시) + 이메일 인증
- Google / Kakao OAuth2 소셜 로그인
- JWT 액세스/리프레시 토큰, 프로필 수정, 비밀번호 변경(소셜 로그인 계정은 차단)

**이벤트/좌석**
- 이벤트 목록 검색(제목 키워드) · 상태 필터 · 공연일 정렬 · 페이지네이션
- Redisson 분산락으로 동일 좌석 동시 홀드 방지

**예약/주문/결제**
- 좌석 임시 홀드 → 예약 → 결제 흐름, PortOne 연동 결제/환불
- 결제 실패 시 예약 자동 취소, 홀드 만료 자동 정리(이벤트 서비스·예약 서비스가 각자 독립적으로 스윕 — 한쪽이
  죽어도 다른 쪽이 커버)
- **동시 예약 30건 부하테스트로 오버셀(중복 판매) 0건을 반복 검증**

**대기열**
- 선착순 입장 대기열, 수평 확장된 `backend` 전체에 고르게 분산(아래 부하테스트 참고)

**프론트엔드**
- 404 페이지 / 에러 바운더리 / 로딩 스켈레톤 / 토스트 알림
- 예약·주문 취소, 로그아웃 확인 다이얼로그
- 마이페이지(이름 변경, 비밀번호 변경)

## 부하테스트로 검증한 것들

설계가 "될 것 같다"가 아니라 "작동한다"는 걸 k6(1000 VU 기준) 부하테스트로 직접 증명했습니다.

| 검증 항목 | 이전 | 이후 |
|---|---|---|
| 서비스 분리(4차) 후 예약 API 지연(p95) | 122ms | 139ms (홉 1개 추가, 허용 범위로 판단) |
| 대규모 동시 요청 시 실패율 | 24% | **0.05%** (nginx 임시 포트 고갈 → 정적 업스트림+keepalive로 해결) |
| `backend` 3레플리카 요청 분산(30건 기준) | 30 : 0 : 0 (전혀 분산 안 됨) | 9 : 10 : 11 (내부 동적 로드밸런서 추가 후) |
| 좌석 중복 예약 | - | 30건 동시 요청에도 0건 |
| 부하테스트 중 발견한 설계 결함 | 취소된 좌석 1,003/5,460석이 영구 재예약 불가 | DB 유니크 제약 수정으로 전부 복구 |

자세한 과정은 [`docs/`](./docs)와 커밋 로그(`nginx 시작 안정성 + backend 로드밸런싱 수정`,
`nginx 커넥션 재사용 + 좌석 영구 재예약 불가 버그 수정` 등)에 남아 있습니다.

## 기술 스택

| 영역 | 스택 |
|---|---|
| Backend | Java 17, Spring Boot 4.1, Spring Security 7, Spring Data JPA, Spring Cloud Gateway(WebMVC), Gradle |
| Frontend | React 19, TypeScript, Vite, react-router-dom v7, axios |
| 메시징/캐시 | Kafka, Redis 7 (Redisson 분산락) |
| DB | MySQL 8 (서비스별 스키마 분리) |
| 결제 | PortOne (테스트 연동) |
| 인프라(local) | Docker Compose, nginx |
| 부하테스트 | k6 |

## 프로젝트 구조

```
ticketing-service/
├─ gateway/               # API Gateway (라우팅 + CORS)
├─ auth-service/          # 인증 · OAuth2 · JWT 발급
├─ event-service/         # 이벤트 · 좌석 · 분산락
├─ reservation-service/   # 예약 · 자가치유 스윕
├─ order-service/         # 주문 · PortOne 결제
├─ backend/               # 대기열(Queue), 수평 확장 대상
├─ nginx/                 # 외부 진입점 + backend 내부 로드밸런서 설정
├─ frontend/              # React SPA (일반 사용자용, :5173)
├─ admin-frontend/        # React SPA (관리자 전용, :5174 — 일반 사이트와 origin/토큰/로그인 분리)
├─ loadtest/              # k6 부하테스트 스크립트
├─ docs/                  # 요구사항/엔티티/API 기획 문서
└─ docker-compose.yml     # 로컬 전체 스택 기동
```

## 로컬 실행 방법

### 1. 환경변수 준비

```bash
cp .env.example .env
```

`.env`에 Google/Kakao OAuth 클라이언트, Gmail SMTP(이메일 인증용), PortOne 테스트 키, 그리고 서명용 시크릿 `JWT_SECRET`·`QR_TOKEN_SECRET`을 채워주세요.

시크릿 두 개는 **기본값이 없습니다**. 소스에 박힌 시크릿은 누구나 아는 시크릿이라 토큰(관리자 토큰까지)을 위조할 수 있기 때문에, 비어 있으면 서비스가 기동을 거부합니다. 32바이트 이상 무작위 값으로 만드세요(서로 다른 값 권장).

```bash
openssl rand -base64 48
# openssl이 없다면
node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"
```
시크릿 두 개를 제외하면 비워둔 항목은 해당 기능만 빠집니다(OAuth 키 → 소셜 로그인, PortOne 키 → 결제, SMTP → 인증 메일 발송).

### 2. 전체 스택 기동

Docker Desktop을 실행한 뒤:

```bash
docker compose up -d
```

MySQL, Redis, Kafka, nginx, gateway와 5개 마이크로서비스가 한 번에 올라옵니다. 진입점은 **nginx 하나**입니다.

- API: `http://localhost:8080` (nginx → gateway → 각 서비스)
- MySQL: `localhost:3306` / Redis: `localhost:6379`

개별 서비스 로그 확인:

```bash
docker compose logs -f gateway reservation-service
```

### 3. 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

`http://localhost:5173`에서 확인 가능하며, `frontend/.env`의 `VITE_API_BASE_URL`로 백엔드(nginx) 주소를 지정합니다.

### 4. 관리자 앱 실행 (선택)

```bash
cd admin-frontend
npm install
npm run dev
```

`http://localhost:5174`에서 열립니다. 관리자는 일반 사이트(5173)에 로그인할 수 없고, 이 앱의 전용 로그인(`POST /api/auth/admin/login`, 세션 15분, 소셜 로그인 불가)으로만 들어옵니다. 관리자 계정은 가입 후 DB에서 `UPDATE users SET role='ADMIN' WHERE email='...'`로 승격합니다.

### 5. 부하테스트 (선택)

게이트웨이는 IP당 요청 수를 제한하고 대기열 진입에는 캡차가 걸려 있으므로(아래 "봇 방어" 참고), 부하 생성기 하나가 한 IP로 보이고 캡차를 풀 수 없는 부하테스트에서는 둘 다 끄고 돌립니다.

```bash
RATE_LIMIT_ENABLED=false CAPTCHA_ENABLED=false docker compose up -d gateway auth-service backend
docker run --network ticketing-service_default --ulimit nofile=200000:200000 \
  -v "$(pwd)/loadtest:/scripts" grafana/k6 run /scripts/reserve_flow.js
```

## 봇 방어 (레이트 리미팅)

게이트웨이가 클라이언트 IP별 토큰 버킷으로 요청을 제한합니다(`gateway/.../ratelimit`, 설정은 `application.yml`의 `rate-limit`). 버스트는 허용하고 지속적인 과다 요청만 `429 TOO_MANY_REQUESTS`(+ `Retry-After`)로 막습니다.

| 대상 | 제한 |
|---|---|
| 관리자 로그인 | 분당 5회 |
| 로그인 / 회원가입 | 분당 10회 / 5회 |
| 대기열 진입 / 좌석 홀드 | 분당 20회 / 20회(버스트 10) |
| 대기열 상태 조회(2초 폴링) | 분당 120회, 전체 제한과 별도 |
| 쿠폰 적용 / 티켓 양도 | 분당 10회 / 5회 (코드·이메일 추측 방지) |
| 그 외 `/api/**` | 분당 600회 |

- IP는 nginx가 덮어쓰는 `X-Real-IP`를 신뢰합니다(게이트웨이는 nginx를 거쳐서만 접근 가능).
- 버킷은 게이트웨이 메모리에 있어 단일 인스턴스 기준입니다. 게이트웨이를 여러 대로 늘리면 Redis 같은 공유 저장소로 옮겨야 합니다.
- 학교·회사처럼 한 IP를 여러 명이 쓰는 환경에서는 제한에 걸릴 수 있어 기본값을 넉넉히 잡았습니다.
- 캡차(reCAPTCHA v2 체크박스)는 **로그인**과 **대기열 진입**에 붙어 있습니다. 브라우저가 받은 토큰을 서버(auth-service, backend)가 구글 siteverify로 확인하고, 구글에 연결할 수 없으면 통과시키지 않고 거절합니다(fail closed). 대기열 상태 조회와 이후 좌석 홀드는 이미 대기열을 통과한 사용자라 따로 걸지 않았습니다.
- 키는 `.env`의 `RECAPTCHA_SECRET_KEY`(서버)와 `frontend/.env`의 `VITE_RECAPTCHA_SITE_KEY`(공개 사이트 키)입니다. 둘 다 비워두면 캡차 없이 동작합니다.

## API 게이트웨이 라우팅

| 경로 | 대상 서비스 |
|---|---|
| `/api/auth/**`, `/oauth2/**`, `/login/oauth2/**` | auth-service |
| `/api/events/**` | event-service |
| `/api/reservations/**` | reservation-service |
| `/api/orders/**` | order-service |
| `/api/queue/**` | backend (nginx 내부 로드밸런서 경유) |

## 문서

- [`docs/`](./docs) — 요구사항 정의서, 엔티티 설계서, API 명세서, OAuth2 설정 정리
