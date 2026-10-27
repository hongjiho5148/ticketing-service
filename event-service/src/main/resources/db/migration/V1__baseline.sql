-- Baseline: the schema as Hibernate (ddl-auto: update) had built it when Flyway was introduced.
-- A database that already has these tables adopts this version as applied (baseline-on-migrate);
-- a new database runs it. Every schema change from here on is a new V<n>__<what>.sql file.

CREATE TABLE `event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(20) NOT NULL DEFAULT 'CONCERT',
  `created_at` datetime(6) NOT NULL,
  `description` text,
  `external_id` varchar(40) DEFAULT NULL,
  `open_at` datetime(6) NOT NULL,
  `start_at` datetime(6) NOT NULL,
  `status` enum('CLOSED','OPEN','UPCOMING') NOT NULL,
  `title` varchar(200) NOT NULL,
  `venue` varchar(200) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_external_id` (`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `open_alert_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `notified_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_alert_user_event` (`user_id`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` text,
  `created_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `rating` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_review_user_event` (`user_id`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `seat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hold_expire_at` datetime(6) DEFAULT NULL,
  `price` int NOT NULL,
  `row_no` int NOT NULL,
  `seat_grade` varchar(20) NOT NULL,
  `seat_no` varchar(20) NOT NULL,
  `seat_number` int NOT NULL,
  `section` varchar(50) NOT NULL,
  `status` enum('AVAILABLE','HOLD','SOLD') NOT NULL,
  `version` int NOT NULL,
  `event_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK3ovym176pe1oxrr87flc9ojcq` (`event_id`),
  CONSTRAINT `FK3ovym176pe1oxrr87flc9ojcq` FOREIGN KEY (`event_id`) REFERENCES `event` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `waitlist_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `notified_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_waitlist_user_event` (`user_id`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `wishlist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wishlist_user_event` (`user_id`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
