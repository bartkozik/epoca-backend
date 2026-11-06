CREATE SCHEMA hostile;
CREATE TABLE hostile.project(id uuid PRIMARY KEY, client_id uuid);
INSERT INTO hostile.project VALUES ('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000004');
CREATE FUNCTION hostile.oauth_audit_metadata_has_keys(jsonb,text[],text[]) RETURNS boolean LANGUAGE sql IMMUTABLE AS 'SELECT true';
SET search_path TO hostile, public;
UPDATE :"schema".work_unit SET client_id='00000000-0000-0000-0000-000000000004';
SELECT bool_and(client_id='00000000-0000-0000-0000-000000000003') FROM :"schema".work_unit;
SELECT NOT :"schema".oauth_audit_metadata_is_safe('{}'::jsonb,'Management','Create');
SET search_path TO :"schema";
DO $$
BEGIN
  BEGIN
    DELETE FROM project;
    RAISE EXCEPTION 'Foreign key allowed an orphan';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
END;
$$;
