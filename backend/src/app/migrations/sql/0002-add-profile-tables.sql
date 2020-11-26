CREATE TABLE profile_type (
  id text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  min_cadence integer NULL,
  max_monitors integer NULL,
  max_contacts integer NULL
);

INSERT INTO profile_type
VALUES ('default', clock_timestamp(), 60, NULL, NULL);

CREATE TABLE profile (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  fullname text NOT NULL DEFAULT '',
  type text NOT NULL DEFAULT 'default'
            REFERENCES profile_type(id) ON DELETE NO ACTION,

  email text NOT NULL,
  pending_email text NULL,
  password text NOT NULL,
  has_password boolean NOT NULL GENERATED ALWAYS AS (password != '!') STORED,
  lang text NULL,
  external_id text NULL
);

CREATE TRIGGER profile__modified_at__tgr
BEFORE UPDATE ON profile
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TABLE contact (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  owner_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  is_enabled boolean NOT NULL DEFAULT true,

  disable_reason text NULL,

  name text NOT NULL,
  type text NOT NULL,

  params jsonb NOT NULL
);

CREATE TRIGGER contact__modified_at__tgr
BEFORE UPDATE ON contact
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE INDEX contact__owner_id__idx
    ON contact(owner_id);

