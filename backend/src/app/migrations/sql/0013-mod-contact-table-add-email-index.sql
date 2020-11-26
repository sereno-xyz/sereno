CREATE UNIQUE INDEX contact__params_email__uidx
    ON contact (owner_id, (params->>'~:email'))
 WHERE type = 'email';
