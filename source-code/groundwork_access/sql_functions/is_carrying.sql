-- groundwork_access.is_carrying: Whether a parent's follow (groundwork_access.follow_…()) is writing its children's optional carried columns now (groundwork_access.carrying 'on').
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.is_carrying() RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork_access.carrying', true), '') = 'on'
$$
