-- Events waiting to go to Kafka. A row is written in the same transaction as the change it describes, and
-- OutboxRelay publishes the unsent ones in id order, so an event can no longer be lost between the database commit
-- and the broker. sent_at is NULL until the broker has acknowledged it; (sent_at, id) serves the relay's
-- "oldest unsent first" poll.
CREATE TABLE `outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(100) NOT NULL,
  `event_key` varchar(100) NOT NULL,
  `payload` varchar(2000) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_sent_id` (`sent_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
