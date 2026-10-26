-- A session seen again lasts longer.
UPDATE groundwork_access.sessions SET session__ends_at = param('session/ends-at')
WHERE session__fingerprint = param('session/fingerprint')
