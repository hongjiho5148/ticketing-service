# 픽시트 (PickSeat)

AI 기반 선착순 이벤트 티켓팅 서비스 — 학습/포트폴리오 목적의 개인 프로젝트.
콘서트 등 한정 좌석에 다수 사용자가 동시에 몰리는 상황을 가정한 예매 서비스이며,
1단계(MVP) → 2단계(동시성 해결) → 3단계(MSA 전환) → 4단계(AI 봇탐지/대기예측) 순으로 확장할 계획입니다.

기획 문서는 [`docs/`](./docs) 폴더 참고.

## 기술 스택

- **Backend**: Java 17, Spring Boot 4.1, Spring Web, Spring Data JPA, Spring Security, Spring Data Redis, Gradle
- **Frontend**: React 19 + Vite + TypeScript, react-router-dom, axios
- **DB**: MySQL 8
- **Cache/향후 대기열**: Redis 7
- **Infra(local)**: Docker Compose

## 프로젝트 구조

```
ticketing-service/
├─ backend/    # Spring Boot API 서버
├─ frontend/   # React SPA
├─ docs/       # 요구사항/엔티티/API 기획 문서
└─ docker-compose.yml   # 로컬 개발용 MySQL + Redis
```

## 로컬 실행 방법

### 1. 인프라 (MySQL, Redis) 기동

Docker Desktop을 실행한 뒤:

```bash
docker compose up -d
```

- MySQL: `localhost:3306` (DB `ticketing`, user/password `ticketing`/`ticketing`)
- Redis: `localhost:6379`

### 2. 백엔드 실행

```bash
cd backend
./gradlew bootRun
```

기본 설정은 `backend/src/main/resources/application.yml` 에 있으며, 환경변수로 값을 덮어쓸 수 있습니다
(`DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET` 등).
서버는 `http://localhost:8080` 에서 기동되며 API base path는 `/api` 입니다.

### 3. 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

`http://localhost:5173` 에서 확인 가능하며, `frontend/.env` 의 `VITE_API_BASE_URL` 로 백엔드 주소를 지정합니다.

## 현재 구현 범위 (1단계 MVP + Redis 골격)

- 회원가입 / 로그인(JWT) / 내 정보 조회
- 이벤트 목록/상세, 좌석 조회
- 좌석 예약(임시 점유, 낙관적 락 기반 동시성 제어) / 예약 취소
- 주문 생성 / 모의 결제 / 주문 내역 조회
- Redis 연동 골격 (`RedisConfig`) — 2단계에서 분산락·대기열(ZSET) 구현에 사용 예정

대기열(Queue), AI 봇 탐지는 2~4단계 확장 범위로 아직 구현되지 않았습니다.
