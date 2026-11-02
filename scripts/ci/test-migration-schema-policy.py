#!/usr/bin/env python3
"""Exercise the migration policy against isolated valid and invalid repositories."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]


class MigrationSchemaPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="migration-policy-")
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        for directory in ["scripts/migrations", "src/main/resources/db"]:
            shutil.copytree(REPOSITORY / directory, self.repository / directory)
        manifest = Path("scripts/backend-verification/accepted/migrations.sha256")
        (self.repository / manifest).parent.mkdir(parents=True)
        shutil.copyfile(REPOSITORY / manifest, self.repository / manifest)

    def check_policy(self):
        return subprocess.run(
            [sys.executable, str(self.repository / "scripts/migrations/check-schema-policy.py")],
            cwd=self.repository,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_rejected(self, message):
        result = self.check_policy()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(message, result.stdout + result.stderr)

    def test_accepts_reviewed_migrations(self):
        result = self.check_policy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS:", result.stdout)

    def test_rejects_changed_applied_version(self):
        migration = next((self.repository / "src/main/resources/db/migration").glob("V1__*.sql"))
        migration.write_text(migration.read_text() + "\n-- unexpected history edit\n")
        self.assert_rejected("Applied versioned migration changed:")

    def test_rejects_new_public_qualified_version(self):
        migration = self.repository / "src/main/resources/db/migration/V999__policy_probe.sql"
        migration.write_text("CREATE TABLE public.policy_probe (id integer);\n")
        self.assert_rejected("Hardcoded application schema:")

    def test_rejects_new_public_qualified_bootstrap(self):
        migration = self.repository / "src/main/resources/db/bootstrap/B999__policy_probe.sql"
        migration.write_text('CREATE TABLE "public".policy_probe (id integer);\n')
        self.assert_rejected("Hardcoded application schema:")

    def test_rejects_baseline_drift(self):
        baseline = self.repository / "src/main/resources/db/bootstrap/B25__configured_application_schema.sql"
        baseline.write_text(baseline.read_text() + "\n-- unexpected baseline edit\n")
        self.assert_rejected("Fresh-install baseline differs from its reviewed generator")

    def test_rejects_missing_configured_migration(self):
        migration = next((self.repository / "src/main/resources/db/configured").glob("V18__*.sql"))
        migration.unlink()
        self.assert_rejected("Configured migration differs from its source:")

    def test_rejects_changed_configured_legacy_version(self):
        migration = next((self.repository / "src/main/resources/db/configured").glob("V1__*.sql"))
        migration.write_text(migration.read_text() + "\n-- unexpected history edit\n")
        self.assert_rejected("Configured migration differs from its source:")

    def test_rejects_changed_configured_migration(self):
        migration = next((self.repository / "src/main/resources/db/configured").glob("V18__*.sql"))
        migration.write_text(migration.read_text() + "\n-- unexpected generated edit\n")
        self.assert_rejected("Configured migration differs from its source:")

    def test_rejects_stale_configured_migration(self):
        migration = self.repository / "src/main/resources/db/configured/V999__policy_probe.sql"
        migration.write_text("SELECT 1;\n")
        self.assert_rejected("Configured location contains stale migrations")

    def test_rejects_stale_nested_configured_migration(self):
        migration = self.repository / "src/main/resources/db/configured/retired/V999__stale_probe.sql"
        migration.parent.mkdir(parents=True)
        migration.write_text("SELECT 1;\n")
        self.assert_rejected("Configured location contains stale migrations")

    def test_rejects_public_qualified_configured_migration(self):
        migration = next((self.repository / "src/main/resources/db/configured").glob("V18__*.sql"))
        migration.write_text(migration.read_text() + "\nCREATE TABLE public.policy_probe (id integer);\n")
        self.assert_rejected("Hardcoded application schema:")

    def test_requires_future_migrations_in_both_locations(self):
        for name in [
            "V999__policy_probe.sql",
            "R__policy_probe.sql",
            "next/V999__nested_probe.sql",
            "next/R__nested_probe.sql",
        ]:
            with self.subTest(migration=name):
                source = self.repository / "src/main/resources/db/migration" / name
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('CREATE TABLE "${flyway:defaultSchema}".policy_probe (id integer);\n')
                self.assert_rejected(f"Configured migration differs from its source: {name}")
                generated = subprocess.run(
                    [sys.executable, str(self.repository / "scripts/migrations/generate-configured.py")],
                    cwd=self.repository,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(generated.returncode, 0, generated.stdout + generated.stderr)
                configured = self.repository / "src/main/resources/db/configured" / name
                self.assertEqual(configured.read_bytes(), source.read_bytes())
                result = self.check_policy()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                source.unlink()
                configured.unlink()


if __name__ == "__main__":
    unittest.main(verbosity=2)
