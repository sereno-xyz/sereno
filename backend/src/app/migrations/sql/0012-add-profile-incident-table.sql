CREATE TABLE profile_incident (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  type text NOT NULL,
  mdata jsonb NOT NULL,

  PRIMARY KEY (profile_id, created_at)
);
