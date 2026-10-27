-- Finding a buyer's queue entries for an event (to hand their slot back when they pay) looks them up by user and event.
-- The table is a history that gets a row for every queue entry, so without this it is a scan of all of them.
CREATE INDEX `idx_waiting_queue_user_event` ON `waiting_queue` (`user_id`, `event_id`);
