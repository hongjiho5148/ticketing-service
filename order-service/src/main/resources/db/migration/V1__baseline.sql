-- Baseline: the schema as Hibernate (ddl-auto: update) had built it when Flyway was introduced.
-- A database that already has these tables adopts this version as applied (baseline-on-migrate);
-- a new database runs it. Every schema change from here on is a new V<n>__<what>.sql file.

CREATE TABLE `coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(30) NOT NULL,
  `discount_type` enum('FLAT','PERCENT') NOT NULL,
  `discount_value` int NOT NULL,
  `max_uses` int NOT NULL,
  `used_count` int NOT NULL,
  `valid_from` datetime(6) NOT NULL,
  `valid_to` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKbg4p9ontpj7adq7yr71h93sdn` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `coupon_redemption` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_code` varchar(30) NOT NULL,
  `coupon_id` bigint NOT NULL,
  `discount_amount` int NOT NULL,
  `order_id` bigint NOT NULL,
  `redeemed_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_coupon_user` (`coupon_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `benefits_redeemed_at` datetime(6) DEFAULT NULL,
  `coupon_code` varchar(30) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `discount_amount` int NOT NULL DEFAULT '0',
  `event_id` bigint NOT NULL,
  `event_start_at` datetime(6) NOT NULL,
  `owner_id` bigint DEFAULT NULL,
  `points_used` int NOT NULL DEFAULT '0',
  `reminder_sent_at` datetime(6) DEFAULT NULL,
  `reservation_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `status` enum('CANCELLED','FAILED','PAID','PARTIALLY_REFUNDED','PENDING') NOT NULL,
  `total_price` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK72hx6qp7pqavtjrwde9gbm13x` (`reservation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` int NOT NULL,
  `method` varchar(20) NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `portone_payment_id` varchar(100) DEFAULT NULL,
  `refunded_amount` int NOT NULL DEFAULT '0',
  `refunded_at` datetime(6) DEFAULT NULL,
  `status` enum('FAILED','SUCCESS') NOT NULL,
  `order_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmf7n8wo2rwrxsd6f3t9ub2mep` (`order_id`),
  CONSTRAINT `FKlouu98csyullos9k25tbpk4va` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `order_id` bigint NOT NULL,
  `payment_id` varchar(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_attempt_payment_id` (`payment_id`),
  KEY `idx_payment_attempt_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `point_account` (
  `user_id` bigint NOT NULL,
  `balance` bigint NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `point_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `delta` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `type` enum('CLAWBACK','EARN','RESTORE','USE') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_point_tx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issued_at` datetime(6) NOT NULL,
  `status` enum('ISSUED','USED') NOT NULL,
  `token_jti` varchar(36) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKfojyg0824t2oqx3wtty7dgerp` (`token_jti`),
  UNIQUE KEY `UKe54nil2s0cb0uu6a27t9ac971` (`order_id`),
  CONSTRAINT `FKfxop4bl4yxuwgij2pa9ghtal6` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ticket_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `from_name` varchar(100) NOT NULL,
  `from_user_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `pending_order_id` bigint DEFAULT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `status` enum('ACCEPTED','CANCELLED','DECLINED','EXPIRED','PENDING') NOT NULL,
  `to_email_masked` varchar(100) NOT NULL,
  `to_user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_pending_order` (`pending_order_id`),
  KEY `idx_transfer_from` (`from_user_id`),
  KEY `idx_transfer_to` (`to_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `verification_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `method` varchar(30) NOT NULL,
  `order_id` bigint NOT NULL,
  `statement_version` varchar(10) NOT NULL,
  `user_id` bigint NOT NULL,
  `verified_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_verification_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
