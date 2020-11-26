DROP TRIGGER monitor_changes_tgr ON monitor CASCADE;
DROP FUNCTION change_notify_trigger () CASCADE;

CREATE FUNCTION notify_table_change() RETURNS trigger AS $trigger$
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
    '~:operation', TG_OP,
    '~:schema',    TG_TABLE_SCHEMA,
    '~:table',     TG_TABLE_NAME,
    '~:record',    row_to_json(rec)
  );

  -- Notify the channel
  PERFORM pg_notify('db_changes', payload);

  RETURN rec;
END;
$trigger$ LANGUAGE plpgsql;

CREATE TRIGGER monitor_changes_tgr AFTER INSERT OR UPDATE OR DELETE
    ON monitor FOR EACH ROW EXECUTE PROCEDURE notify_table_change();

CREATE TRIGGER monitor_changes_tgr AFTER INSERT OR UPDATE OR DELETE
    ON contact FOR EACH ROW EXECUTE PROCEDURE notify_table_change();
