-- Signing out everywhere, or a removed person: their outside issuers' tokens are nobody's at once.
DELETE FROM groundwork_access.outside_subjects WHERE outside_subject__person_id = param('outside-subject/person-id')
