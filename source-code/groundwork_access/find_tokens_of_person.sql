-- A person's tokens that haven't ended, newest first: never their text.
SELECT token__id, token__name, token__shown_start, token__created_at, token__ends_at FROM groundwork_access.tokens
WHERE token__person_id = param('token/person-id') AND token__ends_at > now()
ORDER BY token__created_at DESC, token__id
