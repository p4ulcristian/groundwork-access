-- The views in the folders the app's tables are in (the folders its role is
-- granted) that read past the gates: a view that isn't security_invoker reads
-- its tables with its owner's rights, and a materialized view keeps rows no
-- gate holds. An extension's own are left out, as groundwork-postgres leaves them.
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS view__name,
       c.relkind = 'm'                                                                      AS view__materialized
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ','))
  AND c.relkind IN ('v', 'm')
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
  AND (c.relkind = 'm'
       OR NOT coalesce((SELECT o.option_value::boolean
                        FROM pg_options_to_table(c.reloptions) o
                        WHERE o.option_name = 'security_invoker'), false))
ORDER BY 1
