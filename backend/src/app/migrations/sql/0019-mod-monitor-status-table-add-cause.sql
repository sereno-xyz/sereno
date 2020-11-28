ALTER TABLE monitor_status ADD COLUMN cause jsonb NULL;
ALTER TABLE monitor_entry ADD COLUMN cause jsonb NULL;

UPDATE monitor_status
   SET cause = json_build_object('~:code', '~:unknown', '~:hint', reason)
 WHERE reason is not null;

UPDATE monitor_entry
   SET cause = json_build_object('~:code', '~:unknown', '~:hint', reason)
 WHERE reason is not null;

ALTER TABLE monitor_status DROP COLUMN reason;
ALTER TABLE monitor_entry DROP COLUMN reason;
