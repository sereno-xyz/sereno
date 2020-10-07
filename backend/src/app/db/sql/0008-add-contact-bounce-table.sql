CREATE TABLE contact_bounce (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  contact_id uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  metadata jsonb,

  PRIMARY KEY (profile_id, contact_id, created_at)
);
