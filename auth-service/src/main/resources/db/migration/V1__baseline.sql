-- Baseline: the schema as Hibernate (ddl-auto: update) had built it when Flyway was introduced.
-- A database that already has these tables adopts this version as applied (baseline-on-migrate);
-- a new database runs it. Every schema change from here on is a new V<n>__<what>.sql file.

CREATE TABLE `notification_preference` (
  `user_id` bigint NOT NULL,
  `email_opt_in` bit(1) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  `email_verification_expires_at` datetime(6) DEFAULT NULL,
  `email_verification_token` varchar(100) DEFAULT NULL,
  `email_verified` bit(1) NOT NULL,
  `name` varchar(50) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `provider` enum('GOOGLE','KAKAO','LOCAL') NOT NULL,
  `provider_id` varchar(100) DEFAULT NULL,
  `role` enum('ADMIN','USER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_provider_email` (`provider`,`email`),
  UNIQUE KEY `uk_users_provider_provider_id` (`provider`,`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
