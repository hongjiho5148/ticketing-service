-- Lets several relay instances share the outbox without sending the same row twice: a relay claims a batch for a short
-- lease (claimed_by = its poll's token, claimed_until = when the lease ends) before sending, and others skip rows whose
-- lease has not run out. If the claimant dies the lease simply expires and another instance takes the rows over.
ALTER TABLE `outbox_event`
  ADD COLUMN `claimed_by` varchar(64) DEFAULT NULL,
  ADD COLUMN `claimed_until` datetime(6) DEFAULT NULL;
