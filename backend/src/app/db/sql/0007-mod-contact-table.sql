ALTER TABLE contact
 DROP COLUMN disable_reason,
  ADD COLUMN bounces int NOT NULL DEFAULT 0,
  ADD COLUMN bounced_at timestamptz NULL,
  ADD COLUMN pause_reason jsonb NULL,
  ADD COLUMN disable_reason jsonb NULL,
  ADD COLUMN is_paused boolean NOT NULL DEFAULT false,
  ADD COLUMN is_disabled boolean NOT NULL DEFAULT false;

UPDATE contact SET is_disabled = (not is_enabled);

CREATE INDEX contact_bounced_at_idx
    ON contact(bounced_at)
 WHERE bounced_at is not null;

ALTER TABLE contact
 DROP COLUMN is_enabled;
