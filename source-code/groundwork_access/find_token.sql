-- The person of a token's fingerprint, if it hasn't ended.
SELECT token__id, token__person_id FROM groundwork_access.tokens
WHERE token__fingerprint = param('token/fingerprint') AND token__ends_at > now()
