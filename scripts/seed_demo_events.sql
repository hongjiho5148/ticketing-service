-- 데모용 공연 12개와 좌석을 만든다. 날짜는 실행한 시각 기준(오늘로부터 며칠 뒤)이라 언제 실행해도 "앞으로 열릴 공연"이 된다.
-- 가상의 공연이고(실제 아티스트·작품이 아님) 좌석 배치와 판매도 데모용이다. 같은 제목이 이미 있으면 건너뛰므로 여러 번 실행해도 된다.
--
--   docker exec -i ticketing-mysql mysql -uticketing -pticketing ticketing_event < scripts/seed_demo_events.sql
--
-- 예매는 공연 시작 2시간 전까지만 되므로, 마지막의 "곧 마감" 공연은 시작이 2시간 30분 뒤라 30분 안에 예매가 닫힌다(마감 규칙 시연용).
-- 지우려면:  DELETE s FROM seat s JOIN event e ON e.id = s.event_id WHERE e.description LIKE '%[데모 공연]%';
--            DELETE FROM event WHERE description LIKE '%[데모 공연]%';
SET SESSION cte_max_recursion_depth = 5000;

-- 1) 공연
INSERT INTO event (created_at, title, venue, description, start_at, open_at, status, category)
SELECT NOW(6), v.title, v.venue, CONCAT(v.description, '\n\n[데모 공연] 가상의 공연이며 좌석 배치와 판매는 데모용으로 구성된 것이에요. 실제 예매가 아니에요.'),
       v.start_at, v.open_at, v.status, v.category
FROM (
  SELECT '2026 PICKSEAT 윈터 라이브' AS title, '픽시트 아레나' AS venue, '겨울 시즌을 여는 대형 콘서트. 객석 약 2,400석.\n러닝타임: 약 2시간 30분' AS description,
         TIMESTAMP(DATE(NOW() + INTERVAL 21 DAY), '18:00:00') AS start_at, NOW() - INTERVAL 1 DAY AS open_at, 'OPEN' AS status, 'CONCERT' AS category
  UNION ALL SELECT '밤하늘 어쿠스틱 콘서트', '달빛홀', '잔잔한 기타와 목소리만으로 채우는 저녁.\n러닝타임: 약 1시간 40분',
         TIMESTAMP(DATE(NOW() + INTERVAL 12 DAY), '19:30:00'), NOW() - INTERVAL 1 DAY, 'OPEN', 'CONCERT'
  UNION ALL SELECT '인디 페스티벌 위크엔드', '노을공원 야외무대', '인디 밴드 열두 팀이 이틀 동안 이어 가는 야외 페스티벌.\n입장 시간: 낮 12시부터',
         TIMESTAMP(DATE(NOW() + INTERVAL 40 DAY), '14:00:00'), NOW() + INTERVAL 3 DAY, 'UPCOMING', 'FESTIVAL'
  UNION ALL SELECT '뮤지컬 〈별을 따는 사람들〉', '은하수극장', '별 조각을 모으는 사람들의 이야기를 그린 창작 뮤지컬.\n러닝타임: 2시간 20분(인터미션 20분 포함)',
         TIMESTAMP(DATE(NOW() + INTERVAL 18 DAY), '19:00:00'), NOW() - INTERVAL 2 DAY, 'OPEN', 'MUSICAL'
  UNION ALL SELECT '뮤지컬 〈겨울 편지〉', '은하수극장', '오래된 우체국에 도착한 편지 한 통에서 시작되는 가족 뮤지컬.\n러닝타임: 2시간',
         TIMESTAMP(DATE(NOW() + INTERVAL 33 DAY), '15:00:00'), NOW() + INTERVAL 7 DAY, 'UPCOMING', 'MUSICAL'
  UNION ALL SELECT '연극 〈마지막 기차〉', '소극장 기찻길', '막차를 기다리는 네 사람의 하룻밤.\n러닝타임: 1시간 30분',
         TIMESTAMP(DATE(NOW() + INTERVAL 9 DAY), '20:00:00'), NOW() - INTERVAL 3 DAY, 'OPEN', 'PLAY'
  UNION ALL SELECT '연극 〈두 번째 봄〉', '소극장 기찻길', '이십 년 만에 다시 만난 친구들의 이야기.\n러닝타임: 1시간 40분',
         TIMESTAMP(DATE(NOW() + INTERVAL 27 DAY), '19:30:00'), NOW() - INTERVAL 1 DAY, 'OPEN', 'PLAY'
  UNION ALL SELECT '신년 클래식 갈라 콘서트', '픽시트 콘서트홀', '신년을 여는 협주곡과 왈츠. 객석 약 1,000석.\n러닝타임: 약 2시간(인터미션 포함)',
         TIMESTAMP(DATE(NOW() + INTERVAL 60 DAY), '17:00:00'), NOW() + INTERVAL 14 DAY, 'UPCOMING', 'CLASSIC'
  UNION ALL SELECT '가상 필하모닉 송년 음악회', '픽시트 콘서트홀', '한 해를 마무리하는 오케스트라 연주회.\n러닝타임: 약 1시간 50분',
         TIMESTAMP(DATE(NOW() + INTERVAL 45 DAY), '19:30:00'), NOW() - INTERVAL 1 DAY, 'OPEN', 'CLASSIC'
  UNION ALL SELECT '프로농구 올스타전', '픽시트 체육관', '팬 투표로 뽑은 선수들의 올스타 경기와 이벤트 매치.\n경기 시작 전 식전 행사 있음',
         TIMESTAMP(DATE(NOW() + INTERVAL 50 DAY), '14:00:00'), NOW() - INTERVAL 1 DAY, 'OPEN', 'SPORTS'
  UNION ALL SELECT '빛의 정원 미디어아트 전시', '픽시트 아트센터', '빛과 소리로 만든 몰입형 전시. 시간대별 입장.\n관람 시간: 약 1시간',
         TIMESTAMP(DATE(NOW() + INTERVAL 15 DAY), '11:00:00'), NOW() - INTERVAL 1 DAY, 'OPEN', 'EXHIBITION'
  UNION ALL SELECT '곧 마감 데모 라이브', '달빛홀', '예매 마감 규칙(공연 시작 2시간 전까지)을 보여 주기 위한 공연. 시작이 2시간 30분 뒤라 30분 안에 예매가 닫혀요.',
         NOW() + INTERVAL 150 MINUTE, NOW() - INTERVAL 1 DAY, 'OPEN', 'CONCERT'
) v
WHERE NOT EXISTS (SELECT 1 FROM event e WHERE e.title = v.title);

-- 2) 좌석: 공연마다 구역별 배치(등급, 구역, 열 수, 열당 좌석 수, 가격). 아직 좌석이 없는 데모 공연에만 만든다.
INSERT INTO seat (event_id, seat_grade, section, row_no, seat_number, seat_no, price, status, version)
SELECT e.id, l.grade, l.section, r.n, c.n, CONCAT(r.n, '열 ', c.n, '번'), l.price, 'AVAILABLE', 0
FROM (
  SELECT '2026 PICKSEAT 윈터 라이브' AS title, 'VIP' AS grade, '1층 VIP A구역' AS section, 10 AS `rows`, 20 AS per_row, 165000 AS price
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'VIP', '1층 VIP B구역', 10, 20, 165000
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'R', '1층 R C구역', 14, 24, 132000
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'R', '1층 R D구역', 14, 24, 132000
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'R', '1층 R E구역', 14, 24, 132000
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'S', '2층 S F구역', 16, 30, 99000
  UNION ALL SELECT '2026 PICKSEAT 윈터 라이브', 'S', '2층 S G구역', 16, 30, 99000
  UNION ALL SELECT '밤하늘 어쿠스틱 콘서트', 'R', '1층 A구역', 12, 20, 88000
  UNION ALL SELECT '밤하늘 어쿠스틱 콘서트', 'R', '1층 B구역', 12, 20, 88000
  UNION ALL SELECT '밤하늘 어쿠스틱 콘서트', 'S', '2층 C구역', 10, 24, 66000
  UNION ALL SELECT '인디 페스티벌 위크엔드', 'R', '스탠딩 A구역', 20, 25, 77000
  UNION ALL SELECT '인디 페스티벌 위크엔드', 'S', '스탠딩 B구역', 20, 25, 55000
  UNION ALL SELECT '뮤지컬 〈별을 따는 사람들〉', 'VIP', '1층 A구역', 4, 12, 160000
  UNION ALL SELECT '뮤지컬 〈별을 따는 사람들〉', 'R', '1층 B구역', 10, 14, 130000
  UNION ALL SELECT '뮤지컬 〈별을 따는 사람들〉', 'S', '2층 C구역', 8, 16, 90000
  UNION ALL SELECT '뮤지컬 〈별을 따는 사람들〉', 'S', '2층 D구역', 8, 16, 90000
  UNION ALL SELECT '뮤지컬 〈겨울 편지〉', 'R', '1층 A구역', 10, 14, 99000
  UNION ALL SELECT '뮤지컬 〈겨울 편지〉', 'S', '2층 B구역', 8, 16, 66000
  UNION ALL SELECT '연극 〈마지막 기차〉', 'R', '전석 A구역', 8, 12, 44000
  UNION ALL SELECT '연극 〈마지막 기차〉', 'S', '전석 B구역', 8, 12, 33000
  UNION ALL SELECT '연극 〈두 번째 봄〉', 'R', '전석 A구역', 8, 12, 44000
  UNION ALL SELECT '연극 〈두 번째 봄〉', 'S', '전석 B구역', 8, 12, 33000
  UNION ALL SELECT '신년 클래식 갈라 콘서트', 'VIP', '1층 A구역', 8, 20, 150000
  UNION ALL SELECT '신년 클래식 갈라 콘서트', 'R', '1층 B구역', 12, 22, 110000
  UNION ALL SELECT '신년 클래식 갈라 콘서트', 'S', '2층 C구역', 12, 22, 77000
  UNION ALL SELECT '가상 필하모닉 송년 음악회', 'R', '1층 A구역', 12, 22, 99000
  UNION ALL SELECT '가상 필하모닉 송년 음악회', 'S', '2층 B구역', 12, 22, 66000
  UNION ALL SELECT '프로농구 올스타전', 'R', '1층 A구역', 14, 28, 88000
  UNION ALL SELECT '프로농구 올스타전', 'S', '2층 B구역', 16, 30, 55000
  UNION ALL SELECT '빛의 정원 미디어아트 전시', 'S', '전시 입장 A구역', 10, 30, 22000
  UNION ALL SELECT '곧 마감 데모 라이브', 'R', '1층 A구역', 6, 12, 88000
) l
JOIN event e ON e.title = l.title AND e.description LIKE '%[데모 공연]%'
JOIN (WITH RECURSIVE n AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM n WHERE n < 40) SELECT n FROM n) r ON r.n <= l.`rows`
JOIN (WITH RECURSIVE n AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM n WHERE n < 40) SELECT n FROM n) c ON c.n <= l.per_row
WHERE NOT EXISTS (SELECT 1 FROM seat s WHERE s.event_id = e.id);

SELECT e.id, e.title, e.category, e.status, DATE_FORMAT(e.start_at, '%Y-%m-%d %H:%i') AS start_at,
       (SELECT COUNT(*) FROM seat s WHERE s.event_id = e.id) AS seats
FROM event e WHERE e.description LIKE '%[데모 공연]%' ORDER BY e.start_at;
