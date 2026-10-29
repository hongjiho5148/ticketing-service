-- spike.js가 쓰는 테스트 계정(spike1 ~ spikeN@example.com, 비밀번호는 loadtest1 계정과 같음)을 만든다.
-- 이미 있으면 건너뛰므로 N을 늘려서 다시 실행해도 된다. 인증 메일 확인은 끝난 상태로 만든다(실제 가입 흐름을 거치지 않음).
--
--   docker exec -i ticketing-mysql mysql -uticketing -pticketing ticketing_auth < loadtest/seed_spike_users.sql
--
-- 끝나고 지우려면:  DELETE FROM users WHERE email LIKE 'spike%@example.com';
SET @n = 1000;
SET SESSION cte_max_recursion_depth = 100000;

INSERT INTO users (created_at, email, email_verified, name, password, provider, role)
SELECT NOW(6), CONCAT('spike', seq.n, '@example.com'), b'1', CONCAT('spike', seq.n),
       (SELECT password FROM users WHERE email = 'loadtest1@example.com'), 'LOCAL', 'USER'
FROM (WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < @n) SELECT n FROM seq) AS seq
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.email = CONCAT('spike', seq.n, '@example.com'));

SELECT COUNT(*) AS spike_accounts FROM users WHERE email LIKE 'spike%@example.com';
