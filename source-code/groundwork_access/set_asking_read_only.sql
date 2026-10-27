-- Who is asking, for this transaction only, and the transaction read-only
-- from here on: Postgres refuses every write after it, and it can't be
-- made read-write again (a query has run).
SELECT set_config('transaction_read_only', 'on', true) AS read_only_set,
       set_config('groundwork_access.asking', param('asking'), true) AS asking_set
