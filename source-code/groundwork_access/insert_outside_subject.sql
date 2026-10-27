-- A person's subject at an outside issuer, linked at their sign-in: a
-- token of that issuer with that subject is theirs, whoever's it was before.
INSERT INTO groundwork_access.outside_subjects (outside_subject__issuer, outside_subject__subject, outside_subject__person_id)
VALUES (param('outside-subject/issuer'), param('outside-subject/subject'), param('outside-subject/person-id'))
ON CONFLICT (outside_subject__issuer, outside_subject__subject)
DO UPDATE SET outside_subject__person_id = EXCLUDED.outside_subject__person_id, outside_subject__linked_at = now()
