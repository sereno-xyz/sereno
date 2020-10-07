CREATE TABLE profile_counters (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT date_trunc('month', now()),

  email_notifications bigint NOT NULL DEFAULT 0,

  PRIMARY KEY (profile_id, created_at)
);

ALTER TABLE profile_type
  ADD COLUMN max_email_notifications integer null;
