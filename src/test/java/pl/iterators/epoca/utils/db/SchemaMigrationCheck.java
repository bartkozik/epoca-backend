package pl.iterators.epoca.utils.db;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

public class SchemaMigrationCheck {
  static final String ADMIN =
      System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca");
  static final String USER = System.getenv().getOrDefault("TEST_DB_USER", "postgres");
  static final String PASSWORD = System.getenv().getOrDefault("TEST_DB_PASSWORD", "postgres");

  static String databaseUrl(String database) throws Exception {
    var uri = new java.net.URI(ADMIN.substring(5));
    return "jdbc:"
        + new java.net.URI(
            uri.getScheme(),
            uri.getUserInfo(),
            uri.getHost(),
            uri.getPort(),
            "/" + database,
            null,
            null);
  }

  static String root;

  static void sql(Connection c, String s) throws Exception {
    try (var st = c.createStatement()) {
      st.execute(s);
    }
  }

  static String value(Connection c, String s) throws Exception {
    try (var st = c.createStatement();
        var rs = st.executeQuery(s)) {
      rs.next();
      return rs.getString(1);
    }
  }

  static void check(boolean b, String m) {
    if (!b) throw new AssertionError(m);
  }

  static Flyway flyway(String db, String schema, String user, boolean bootstrap, String target)
      throws Exception {
    var cfg =
        Flyway.configure()
            .dataSource(
                databaseUrl(db) + "?currentSchema=" + schema,
                user,
                user.equals(USER) ? PASSWORD : "postgres")
            .schemas(schema, "quartz")
            .defaultSchema(schema)
            .locations(
                bootstrap
                    ? new String[] {
                      "filesystem:" + root + "/src/main/resources/db/migration",
                      "filesystem:" + root + "/src/main/resources/db/bootstrap"
                    }
                    : new String[] {"filesystem:" + root + "/src/main/resources/db/migration"});
    if (target != null) cfg.target(target);
    return cfg.load();
  }

  static String history(Connection c, String schema) throws Exception {
    return value(
        c,
        "SELECT coalesce(string_agg(version||':'||checksum,',' ORDER BY installed_rank),'') FROM "
            + schema
            + ".flyway_schema_history WHERE version IS NOT NULL AND type='SQL'");
  }

  public static void main(String[] args) throws Exception {
    root = Path.of("").toAbsolutePath().toString();
    String db = "schema_contract_" + UUID.randomUUID().toString().replace("-", "");
    try (var admin = DriverManager.getConnection(ADMIN, USER, PASSWORD)) {
      String freshDatabase = db + "_fresh";
      sql(admin, "CREATE DATABASE " + freshDatabase);
      try {
        var freshPublic = flyway(freshDatabase, "public", USER, true, null);
        freshPublic.migrate();
        freshPublic.validate();
        check(freshPublic.migrate().migrationsExecuted == 0, "fresh public is not idempotent");
        try (var fresh = DriverManager.getConnection(databaseUrl(freshDatabase), USER, PASSWORD)) {
          check(
              value(
                      fresh,
                      "SELECT count(*) FROM public.flyway_schema_history WHERE type='SQL_BASELINE'")
                  .equals("1"),
              "fresh public baseline not used");
        }
      } finally {
        sql(admin, "DROP DATABASE " + freshDatabase + " WITH (FORCE)");
      }
      boolean roleCreated = false;
      sql(admin, "CREATE DATABASE " + db);
      try (var c = DriverManager.getConnection(databaseUrl(db), USER, PASSWORD)) {
        var legacy = flyway(db, "public", USER, false, "18");
        legacy.migrate();
        check(
            legacy.info().current().getVersion().toString().equals("18"),
            "historical target changed");
        sql(
            c,
            "INSERT INTO public.\"user\""
                + " (id,first_name,last_name,is_super_admin,created_at,modified_at) VALUES"
                + " ('00000000-0000-0000-0000-000000000099','Existing','Person',false,now(),now())");
        String oldUser = value(c, "SELECT row_to_json(u)::jsonb::text FROM public.\"user\" u");
        String old = history(c, "public");
        var upgrade = flyway(db, "public", USER, true, null);
        upgrade.migrate();
        upgrade.validate();
        check(history(c, "public").startsWith(old), "legacy checksums changed");
        check(
            value(c, "SELECT (row_to_json(u)::jsonb - 'account_type')::text FROM public.\"user\" u")
                .equals(oldUser),
            "existing user data changed");
        String before = history(c, "public");
        upgrade.migrate();
        upgrade.validate();
        check(history(c, "public").equals(before), "repeat migration changed history");
        sql(c, "CREATE ROLE " + db + " LOGIN PASSWORD 'postgres'");
        roleCreated = true;
        sql(c, "GRANT CREATE ON DATABASE " + db + " TO " + db);
        sql(c, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        for (String schema : new String[] {"application_a", "application_b"}) {
          var fresh = flyway(db, schema, db, true, null);
          fresh.migrate();
          fresh.validate();
          check(fresh.info().current().getVersion().toString().equals("37"), "latest migration version");
          check(
              value(
                      c,
                      "SELECT count(*) FROM "
                          + schema
                          + ".flyway_schema_history WHERE type='SQL_BASELINE'")
                  .equals("1"),
              "baseline not used");
          check(
              value(
                      c,
                      "SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid"
                          + " JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_class referenced"
                          + " ON referenced.oid=k.confrelid JOIN pg_namespace target ON"
                          + " target.oid=referenced.relnamespace WHERE k.contype='f' AND"
                          + " n.nspname='"
                          + schema
                          + "' AND target.nspname<>'"
                          + schema
                          + "'")
                  .equals("0"),
              "foreign key crosses application schema");
          int count = fresh.migrate().migrationsExecuted;
          check(count == 0, "repeat migration not idempotent");
          check(
              value(
                      c,
                      "SELECT count(*) FROM pg_tables WHERE schemaname='"
                          + schema
                          + "' AND tablename<>'flyway_schema_history'")
                  .equals(
                      value(
                          c,
                          "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND"
                              + " tablename<>'flyway_schema_history'")),
              "table count mismatch");
          try (var app =
              DriverManager.getConnection(
                  databaseUrl(db) + "?currentSchema=" + schema, db, "postgres")) {
            check(value(app, "SELECT current_schema()").equals(schema), "connection schema drift");
            check(
                value(app, "SELECT count(*) FROM oauth_client").equals("0"),
                "OAuth query wrong schema");
            try {
              sql(app, "CREATE TABLE public.forbidden_probe(id int)");
              throw new AssertionError("public write allowed");
            } catch (SQLException e) {
              check(e.getSQLState().equals("42501"), "unexpected privilege failure");
            }
            sql(app, "BEGIN");
            sql(app, "LOCK TABLE project IN ROW SHARE MODE");
            sql(app, "ROLLBACK");
            sql(app, "UPDATE mail_history SET delivered=CURRENT_TIMESTAMP WHERE false");
            check(
                value(app, "SELECT oauth_audit_metadata_is_safe('{}'::jsonb,'Management','Create')")
                    .equals("f"),
                "helper contract");
          }
        }
        for (String schema : new String[] {"application_a", "application_b"}) {
          String client =
              schema.equals("application_a")
                  ? "00000000-0000-0000-0000-000000000003"
                  : "00000000-0000-0000-0000-000000000004";
          sql(c, "SET search_path TO " + schema);
          sql(
              c,
              "INSERT INTO \"user\" (id,first_name,last_name,is_super_admin,created_at,modified_at)"
                  + " VALUES"
                  + " ('00000000-0000-0000-0000-000000000001','Schema','Test',false,now(),now())");
          sql(
              c,
              "INSERT INTO organization"
                  + " (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES"
                  + " ('00000000-0000-0000-0000-000000000002','Schema','schema',now(),'00000000-0000-0000-0000-000000000001',now(),'00000000-0000-0000-0000-000000000001')");
          sql(
              c,
              "INSERT INTO client"
                  + " (id,name,color,organization_id,created_at,created_by,modified_at,modified_by,logo,type,texture)"
                  + " VALUES ('"
                  + client
                  + "','Client','#000000','00000000-0000-0000-0000-000000000002',now(),'00000000-0000-0000-0000-000000000001',now(),'00000000-0000-0000-0000-000000000001','','External','')");
          sql(
              c,
              "INSERT INTO project"
                  + " (id,name,organization_id,client_id,created_at,created_by,modified_at,modified_by)"
                  + " VALUES"
                  + " ('00000000-0000-0000-0000-000000000005','Project','00000000-0000-0000-0000-000000000002','"
                  + client
                  + "',now(),'00000000-0000-0000-0000-000000000001',now(),'00000000-0000-0000-0000-000000000001')");
        }
        sql(c, "SET search_path TO application_b, public");
        sql(
            c,
            "INSERT INTO application_a.work_unit"
                + " (id,organization_id,user_id,project_id,date,duration,description,created_at,modified_at,client_id,state)"
                + " VALUES"
                + " ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000005',current_date,interval"
                + " '1 hour','Schema"
                + " test',now(),now(),'00000000-0000-0000-0000-000000000004','Active')");
        check(
            value(c, "SELECT client_id FROM application_a.work_unit").endsWith("0003"),
            "trigger read decoy schema");
        check(
            value(c, "SELECT count(*) FROM application_b.work_unit").equals("0"),
            "trigger wrote decoy schema");
        sql(
            c,
            "CREATE OR REPLACE FUNCTION application_b.oauth_audit_metadata_has_keys(input_value"
                + " jsonb,allowed_keys text[],required_keys text[]) RETURNS boolean LANGUAGE sql"
                + " IMMUTABLE AS 'SELECT true'");
        check(
            value(
                    c,
                    "SELECT"
                        + " application_a.oauth_audit_metadata_is_safe('{}'::jsonb,'Management','Create')")
                .equals("f"),
            "nested helper read decoy schema");
        sql(
            c,
            "UPDATE application_a.work_unit SET client_id='00000000-0000-0000-0000-000000000004'");
        check(
            value(c, "SELECT client_id FROM application_a.work_unit").endsWith("0003"),
            "update trigger read decoy schema");
        try {
          sql(c, "DELETE FROM application_a.project");
          throw new AssertionError("foreign key allowed orphaned work unit");
        } catch (SQLException error) {
          check(error.getSQLState().equals("23503"), "unexpected foreign key error");
        }
        System.out.println(
            "PASS: hostile search_path, conflicting project IDs, trigger client derivation and"
                + " nested OAuth helper isolation");
        System.out.println(
            "PASS: legacy target18, upgrade27 checksums, repeat migrate/validate, two restricted"
                + " schemas, JDBC scope, OAuth read, project lock, mail update");
      } finally {
        sql(admin, "DROP DATABASE " + db + " WITH (FORCE)");
        if (roleCreated) sql(admin, "DROP ROLE " + db);
      }
    }
  }
}
