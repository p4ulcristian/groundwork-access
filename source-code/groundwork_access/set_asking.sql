-- Who is asking, for this transaction only.
SELECT set_config('groundwork_access.asking', param('asking'), true) AS asking_set
