ALTER TABLE profile
  ADD COLUMN is_active boolean DEFAULT false,
 DROP COLUMN pending_email;

CREATE INDEX profile_id_created_at_when_not_active_idx
    ON profile (id, created_at)
 WHERE is_active is false;
