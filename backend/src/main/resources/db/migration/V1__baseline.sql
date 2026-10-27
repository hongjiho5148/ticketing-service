-- Baseline: the schema as Hibernate (ddl-auto: update) had built it when Flyway was introduced.
-- A database that already has these tables adopts this version as applied (baseline-on-migrate);
-- a new database runs it. Every schema change from here on is a new V<n>__<what>.sql file.

CREATE TABLE `waiting_queue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entered_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `passed_at` datetime(6) DEFAULT NULL,
  `queue_token` varchar(40) NOT NULL,
  `rank_no` bigint NOT NULL,
  `status` enum('EXPIRED','PASSED','WAITING') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKlhc7079haf31xsx341gyv4v76` (`queue_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
