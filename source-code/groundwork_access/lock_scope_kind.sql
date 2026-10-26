-- One scope of a kind made at a time, until the transaction ends: two
-- transactions can't both make the first scope of a kind that has one.
SELECT 1 AS scope_lock__held FROM (SELECT pg_advisory_xact_lock(hashtext(param('scope/lock-name')))) AS taken
