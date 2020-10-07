CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE FUNCTION update_modified_at()
  RETURNS TRIGGER AS $updt$
  BEGIN
    NEW.modified_at := clock_timestamp();
    RETURN NEW;
  END;
$updt$ LANGUAGE plpgsql;

CREATE FUNCTION time_bucket(interval, timestamptz) RETURNS timestamptz AS $$
DECLARE
  secs1 int := extract(epoch from $1);
  secs2 int := extract(epoch from $2);
BEGIN
  RETURN to_timestamp(floor((secs2 / secs1)) * secs1);
END;
$$ LANGUAGE plpgsql;

CREATE AGGREGATE array_cat_agg(anyarray) (
  SFUNC=array_cat,
  STYPE=anyarray
);
