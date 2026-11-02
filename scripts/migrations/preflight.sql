-- Read-only evidence for the deployment owner. Requires psql -v app_schema=...
\set ON_ERROR_STOP on
\if :{?app_schema}
\else
\echo 'Set app_schema to the intended application schema.'
\quit 2
\endif
BEGIN READ ONLY;
SELECT current_database() AS database, current_user AS role,
       current_setting('server_version') AS postgres_version,
       current_schema() AS connection_schema,
       current_setting('search_path') AS connection_search_path;
SELECT nspname AS schema, pg_get_userbyid(nspowner) AS owner,
       has_schema_privilege(current_user, oid, 'USAGE') AS can_use,
       has_schema_privilege(current_user, oid, 'CREATE') AS can_create
FROM pg_namespace WHERE nspname IN (:'app_schema', 'public', 'quartz');
SELECT n.nspname AS schema, c.relname AS history_table, pg_get_userbyid(c.relowner) AS owner
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind='r' AND c.relname IN ('flyway_schema_history', 'schema_version')
ORDER BY n.nspname, c.relname;
SELECT format('SELECT %L AS schema, %L AS history_table, installed_rank, version, description, type, script, checksum, success FROM %I.%I ORDER BY installed_rank',
              n.nspname, c.relname, n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind='r' AND n.nspname=:'app_schema'
  AND c.relname IN ('flyway_schema_history', 'schema_version')
\gexec
SELECT count(*) AS application_tables FROM pg_tables WHERE schemaname=:'app_schema';
COMMIT;
