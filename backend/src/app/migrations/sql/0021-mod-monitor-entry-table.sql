ALTER TABLE monitor_entry
  ADD COLUMN metadata jsonb NULL DEFAULT NULL;

ALTER TABLE monitor_entry
ALTER COLUMN latency DROP NOT NULL;
