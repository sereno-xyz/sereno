ALTER TABLE contact ADD COLUMN validated_at timestamptz NULL;

UPDATE contact SET validated_at=now();

ALTER TABLE contact ALTER COLUMN validated_at SET NOT NULL;
