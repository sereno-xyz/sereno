ALTER TABLE contact ADD COLUMN validated_at timestamptz NULL;

UPDATE contact SET validated_at=now();
