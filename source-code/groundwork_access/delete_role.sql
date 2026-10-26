-- A role gone; its actions went first, and nobody holds it.
DELETE FROM groundwork_access.roles WHERE role__id = param('role/id')
