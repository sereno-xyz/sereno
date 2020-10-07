CREATE TABLE http_session (
  id text,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  profile_id uuid REFERENCES profile(id) ON DELETE CASCADE,
  user_agent text NULL,

  PRIMARY KEY (id, profile_id)
);
