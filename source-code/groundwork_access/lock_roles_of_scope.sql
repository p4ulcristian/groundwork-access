-- One change to a scope's roles at a time, until the transaction ends: two
-- transactions can't both give a name the scope has once.
SELECT 1 AS role_lock__held FROM (SELECT pg_advisory_xact_lock(hashtext(param('role/lock-name')))) AS taken
