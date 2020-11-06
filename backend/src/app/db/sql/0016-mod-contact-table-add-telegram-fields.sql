CREATE INDEX contact__telegram__chat_id__idx
    ON contact((params->>'~:chat-id'))
 WHERE type = 'telegram';

ALTER TABLE monitor_contact_rel
  ADD COLUMN id uuid NOT NULL DEFAULT uuid_generate_v4();

CREATE UNIQUE INDEX monitor_contact_rel__id__uidx
    ON monitor_contact_rel(id);

