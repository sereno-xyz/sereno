CREATE TABLE generic_token (
  token text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  valid_until timestamptz NOT NULL,
  content jsonb NOT NULL
);

COMMENT ON TABLE generic_token IS 'Table for generic tokens storage';
