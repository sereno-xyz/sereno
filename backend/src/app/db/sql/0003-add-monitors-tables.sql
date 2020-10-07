
CREATE TABLE monitor (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  owner_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  monitored_at timestamptz NULL,

  name text NOT NULL,
  type text NOT NULL,

  cadence integer NOT NULL,
  cron_expr text NOT NULL,
  status text NULL,

  tags text[] NOT NULL DEFAULT '{}'::text[],
  params jsonb NOT NULL
);

CREATE INDEX monitor__owner_id__idx
    ON monitor(owner_id);

CREATE TRIGGER monitor__modified_at__tgr
BEFORE UPDATE ON monitor
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TABLE monitor_schedule (
  monitor_id uuid NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  scheduled_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY(scheduled_at, monitor_id)
);

CREATE TABLE monitor_contact_rel (
  monitor_id uuid NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,
  contact_id uuid NOT NULL REFERENCES contact(id) ON DELETE CASCADE,

  PRIMARY KEY (monitor_id, contact_id)
);

CREATE FUNCTION change_notify_trigger() RETURNS trigger AS $trigger$
DECLARE
  rec RECORD;
  payload TEXT;
BEGIN
  -- Set record row depending on operation
  CASE TG_OP
  WHEN 'INSERT', 'UPDATE' THEN
     rec := NEW;
  WHEN 'DELETE' THEN
     rec := OLD;
  ELSE
     RAISE EXCEPTION 'Unknown TG_OP: "%". Should not occur!', TG_OP;
  END CASE;

  -- Build the payload
  payload := json_build_object(
    'operation', TG_OP,
    'schema',    TG_TABLE_SCHEMA,
    'table',     TG_TABLE_NAME,
    'id',        rec.id,
    'name',      rec.name,
    'owner-id',  rec.owner_id,
    'cadence',   rec.cadence,
    'cron-expr', rec.cron_expr,
    'status',    rec.status
  );

  -- Notify the channel
  PERFORM pg_notify('db_changes', payload);

  RETURN rec;
END;
$trigger$ LANGUAGE plpgsql;

CREATE TRIGGER monitor_changes_tgr AFTER INSERT OR UPDATE OR DELETE
    ON monitor FOR EACH ROW EXECUTE PROCEDURE change_notify_trigger();

CREATE TABLE monitor_entry (
  monitor_id uuid NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  reason text NULL,
  latency integer NOT NULL,
  status text NOT NULL,

  PRIMARY KEY (monitor_id, created_at)
);

ALTER TABLE monitor_entry
  SET (autovacuum_freeze_min_age = 0,
       autovacuum_freeze_max_age = 100000);

CREATE TABLE monitor_status (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  monitor_id uuid NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,

  reason text NULL,
  status text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at timestamptz NULL
);

CREATE INDEX monitor_status__monitor_id__idx
    ON monitor_status(monitor_id);

