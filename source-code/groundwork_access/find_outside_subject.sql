-- The person a subject at an outside issuer is linked to, if one is.
SELECT outside_subject__person_id FROM groundwork_access.outside_subjects
WHERE outside_subject__issuer = param('outside-subject/issuer') AND outside_subject__subject = param('outside-subject/subject')
