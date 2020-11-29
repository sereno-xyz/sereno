UPDATE contact
   SET type = 'owner',
       name = 'Primary Contact',
       params = '{}'::json,
       validated_at = profile.created_at,
       created_at = profile.created_at
  FROM profile
 WHERE profile.id = contact.owner_id
   AND date_trunc('minute', profile.created_at) = date_trunc('minute', contact.created_at)
   AND contact.type = 'email';
