CREATE TABLE server_prop (
  id text PRIMARY KEY,
  content jsonb,
  preload boolean DEFAULT false
);

ALTER TABLE server_prop
  ALTER COLUMN id SET STORAGE external,
  ALTER COLUMN content SET STORAGE external;
