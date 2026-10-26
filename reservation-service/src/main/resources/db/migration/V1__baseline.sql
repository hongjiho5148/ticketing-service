-- Baseline: the schema as Hibernate (ddl-auto: update) had built it when Flyway was introduced.
-- A database that already has these tables adopts this version as applied (baseline-on-migrate);
-- a new database runs it. Every schema change from here on is a new V<n>__<what>.sql file.

CREATE TABLE `reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL DEFAULT '0',
  `hold_expire_at` datetime(6) NOT NULL,
  `seat_id` bigint NOT NULL,
  `status` enum('CANCELLED','CONFIRMED','EXPIRED','HOLDING') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_reservation_user_event_status` (`user_id`,`event_id`,`status`),
  KEY `idx_reservation_status_hold_expire` (`status`,`hold_expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
