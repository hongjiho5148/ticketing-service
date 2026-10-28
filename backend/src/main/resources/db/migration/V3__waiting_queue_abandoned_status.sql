-- Entries whose person left the line without being let in (see QueueAdmissionSweeper) get their own status instead of staying
-- WAITING forever in the history.
ALTER TABLE `waiting_queue` MODIFY `status` enum('EXPIRED','PASSED','WAITING','ABANDONED') NOT NULL;
