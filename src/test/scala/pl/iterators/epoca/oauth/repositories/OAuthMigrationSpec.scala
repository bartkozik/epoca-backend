package pl.iterators.epoca.oauth.repositories

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.postgresql.util.PSQLException
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditEventType, OAuthAuditFailureCategory}

import java.net.URI
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.OffsetDateTime
import java.util.UUID
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class OAuthMigrationSpec extends SpecificationLike {
  sequential

  private val baseDatabaseUrl   = sys.env.getOrElse("TEST_DB_URL", "jdbc:postgresql://localhost:5432/epoca")
  private val databaseUser      = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val databasePassword  = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
  private val migrationLocation = sys.env.getOrElse("OAUTH_MIGRATION_LOCATION", "classpath:db/migration")

  private val fixedScopes =
    Seq("organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export")
  private val validCreateAuditMetadata =
    """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
  private val validInvalidClientAuditMetadata = """{"failureCategory":"unknown-client"}"""

  private case class FixtureIds(
    humanId: UUID,
    secondHumanId: UUID,
    organizationId: UUID,
    secondOrganizationId: UUID,
    projectId: UUID,
    secondProjectId: UUID)

  "OAuth migrations" should {
    "align Scala with the current closed PostgreSQL audit catalogs" in {
      OAuthAuditEventType.values
        .map(_.entryName)
        .toSet shouldEqual Set("Create", "Update", "Rotate", "Revoke", "TokenIssued", "InvalidClient", "InvalidScope", "RateLimit")
      OAuthAuditFailureCategory.values
        .map(_.entryName)
        .toSet shouldEqual Set(
        "malformed-credentials",
        "unknown-client",
        "invalid-secret",
        "revoked-client",
        "malformed-scope",
        "unknown-scope",
        "ungranted-scope",
        "ip-rate-limit",
        "client-rate-limit",
        "expired-client"
      )
    }

    "keep the V24 migration byte-for-byte unchanged" in {
      val migration = Paths.get("src/main/resources/db/migration/V24__oauth_client_credentials.sql")
      sha256(Files.readAllBytes(migration)) shouldEqual "aa53dd0e89e6fef9e29ac1079404b020e4c86d83a9962eb870fe527930ce8e1e"
    }

    "retain the V24-only audit catalog until V25 is applied" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("24"))).migrate()
      val fixture = seedFixture(databaseUrl)

      flywayCurrentVersion(databaseUrl) shouldEqual "24"
      assertStatementViolation(databaseUrl, "oauth_audit_event_metadata_schema_check") { connection =>
        val _ = insertAuditEvent(connection, fixture, "Authentication", "InvalidClient", """{"failureCategory":"expired-client"}""")
      }
      true should beTrue
    }

    "preserve an existing user as Human when upgrading from V23 through V31" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("23"))).migrate()
      val existingUserId = UUID.randomUUID()
      insertUser(databaseUrl, existingUserId)

      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("31"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "31"
      accountType(databaseUrl, existingUserId) shouldEqual "Human"
    }

    "migrate a fresh database through V31 and keep defaulted human rows readable" in withMigratedDatabase { (databaseUrl, fixture) =>
      flywayCurrentVersion(databaseUrl) shouldEqual "31"
      accountType(databaseUrl, fixture.humanId) shouldEqual "Human"
      existingTables(databaseUrl) should contain(
        allOf("oauth_client", "oauth_client_scope", "oauth_client_project", "oauth_audit_event", "oauth_rate_limit_bucket")
      )
      indexDefinition(databaseUrl, "work_unit_oauth_report_project_idx")
        .contains("(organization_id, project_id)") should beTrue
      columnNames(databaseUrl, "oauth_client") should contain(allOf("expires_at", "last_used_at"))
    }

    "upgrade V24 clients through V31 without backfilling administrative timestamps" in withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("24"))).migrate()
      val fixture          = seedFixture(databaseUrl)
      val serviceAccountId = UUID.randomUUID()
      val clientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "Historical client", "AllProjects", None)
      }

      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("31"))).migrate()

      flywayCurrentVersion(databaseUrl) shouldEqual "31"
      oauthClientLifecycle(databaseUrl, clientId) shouldEqual (None, None)
    }

    "enforce both administrative timestamp constraints" in withMigratedDatabase { (databaseUrl, fixture) =>
      val serviceAccountId = UUID.randomUUID()
      val clientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "Timestamp checks", "AllProjects", None)
      }

      assertStatementViolation(databaseUrl, "oauth_client_expires_at_after_created_at_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET expires_at = created_at WHERE id = ?", clientId)
      }
      assertStatementViolation(databaseUrl, "oauth_client_last_used_at_not_before_created_at_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET last_used_at = created_at - INTERVAL '1 microsecond' WHERE id = ?", clientId)
      }
      true should beTrue
    }

    "accept the nearest legal administrative timestamp boundaries" in withMigratedDatabase { (databaseUrl, fixture) =>
      val serviceAccountId = UUID.randomUUID()
      val clientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "Timestamp boundaries", "AllProjects", None)
      }

      withTransaction(databaseUrl) { connection =>
        update(
          connection,
          "UPDATE public.oauth_client SET expires_at = created_at + INTERVAL '1 microsecond', last_used_at = created_at WHERE id = ?",
          clientId
        )
      }

      val (createdAt, expiresAt, lastUsedAt) = oauthClientAdministrativeTimestamps(databaseUrl, clientId)
      expiresAt shouldEqual Some(createdAt.plusNanos(1000L))
      lastUsedAt shouldEqual Some(createdAt)
    }

    "declare a partial expiry index for active non-revoked expiring clients" in withMigratedDatabase { (databaseUrl, _) =>
      val definition = indexDefinition(databaseUrl, "oauth_client_active_expires_at_idx")
      definition.contains("(expires_at)") should beTrue
      definition.contains("status = 'Active'::text") should beTrue
      definition.contains("revoked_at IS NULL") should beTrue
      definition.contains("expires_at IS NOT NULL") should beTrue
    }

    "commit legal AllProjects and SelectedProjects service accounts with deferred constraints" in withMigratedDatabase { (databaseUrl, fixture) =>
      val allProjectsUserId      = UUID.randomUUID()
      val selectedProjectsUserId = UUID.randomUUID()
      withTransaction(databaseUrl) { connection =>
        val allProjectsClientId = createLegalServiceAccount(connection, fixture, allProjectsUserId, "All projects", "AllProjects", None)
        fixedScopes.tail.foreach(insertScope(connection, allProjectsClientId, _))
        createLegalServiceAccount(connection, fixture, selectedProjectsUserId, "Selected projects", "SelectedProjects", Some(fixture.projectId))
      }

      scalarLong(
        databaseUrl,
        s"SELECT count(*) FROM public.oauth_client WHERE service_account_user_id IN ('$allProjectsUserId', '$selectedProjectsUserId')"
      ) shouldEqual 2L
    }

    "reject incomplete service-account creation only when the transaction commits" in withMigratedDatabase { (databaseUrl, fixture) =>
      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        insertServiceAccount(connection, UUID.randomUUID())
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "Missing membership", "AllProjects")
        insertScope(connection, clientId)
      }
      true should beTrue
    }

    "reject duplicate client or membership shapes and wrong or privileged memberships at commit" in withMigratedDatabase { (databaseUrl, fixture) =>
      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "Duplicate membership", "AllProjects")
        insertScope(connection, clientId)
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val firstClientId = insertOAuthClient(connection, fixture, serviceAccountId, "First binding", "AllProjects")
        insertScope(connection, firstClientId)
        val secondClientId = insertOAuthClient(connection, fixture, serviceAccountId, "Second binding", "AllProjects")
        insertScope(connection, secondClientId)
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.secondOrganizationId, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "Wrong organization", "AllProjects")
        insertScope(connection, clientId)
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId, roles = Seq("Owner"))
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "Privileged service", "AllProjects")
        insertScope(connection, clientId)
      }
      true should beTrue
    }

    "reject human attachment, account-type swaps, and independent membership relocation or removal at commit" in withMigratedDatabase {
      (databaseUrl, fixture) =>
        assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
          val clientId = insertOAuthClient(connection, fixture, fixture.humanId, "Human client", "AllProjects")
          insertScope(connection, clientId)
        }

        assertCommitViolation(databaseUrl, "oauth_user_account_type_immutable_check") { connection =>
          update(connection, "UPDATE public.\"user\" SET account_type = 'ServiceAccount' WHERE id = ?", fixture.secondHumanId)
        }

        val serviceAccountId = UUID.randomUUID()
        val clientId = withTransaction(databaseUrl) { connection =>
          createLegalServiceAccount(connection, fixture, serviceAccountId, "Stable service", "AllProjects", None)
        }

        assertCommitViolation(databaseUrl, "oauth_user_account_type_immutable_check") { connection =>
          update(connection, "UPDATE public.\"user\" SET account_type = 'Human' WHERE id = ?", serviceAccountId)
        }
        assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
          update(connection, "UPDATE public.\"user\" SET is_super_admin = TRUE WHERE id = ?", serviceAccountId)
        }
        assertCommitViolation(databaseUrl, "oauth_client_human_actor_check") { connection =>
          update(connection, "UPDATE public.oauth_client SET modified_by = ? WHERE id = ?", serviceAccountId, clientId)
        }
        assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
          update(
            connection,
            "UPDATE public.organization_user SET organization_id = ? WHERE user_id = ?",
            fixture.secondOrganizationId,
            serviceAccountId
          )
        }
        assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
          update(connection, "DELETE FROM public.organization_user WHERE user_id = ?", serviceAccountId)
        }
        true should beTrue
    }

    "reject identities and refresh tokens attached to a service account at commit" in withMigratedDatabase { (databaseUrl, fixture) =>
      val serviceAccountId = UUID.randomUUID()
      withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "No login", "AllProjects", None)
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_identity_check") { connection =>
        insert(
          connection,
          """
            INSERT INTO public.user_identity (
              id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at
            ) VALUES (?, ?, 'MailAddress', ?, TRUE, NULL, TRUE, NULL, CURRENT_TIMESTAMP)
          """,
          UUID.randomUUID(),
          serviceAccountId,
          s"service-${UUID.randomUUID()}@example.test"
        )
      }

      assertCommitViolation(databaseUrl, "oauth_service_account_refresh_token_check") { connection =>
        insert(
          connection,
          """
            INSERT INTO public.refresh_token (
              user_id, device_id, user_agent, value, authed_by, last_authed_at, created_at, expired_at
            ) VALUES (?, ?, NULL, ?, '{}'::uuid[], CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
          """,
          serviceAccountId,
          s"device-${UUID.randomUUID()}",
          s"refresh-${UUID.randomUUID()}"
        )
      }
      true should beTrue
    }

    "enforce OAuth client uniqueness and fixed status, mode, scope, and positive versions" in withMigratedDatabase { (databaseUrl, fixture) =>
      val publicClientId = s"epoca_${UUID.randomUUID().toString.replace("-", "")}"
      assertStatementViolation(databaseUrl, "oauth_client_client_id_key") { connection =>
        val _ = insertOAuthClient(connection, fixture, fixture.humanId, "Public ID first", "AllProjects", publicClientId = publicClientId)
        val _ = insertOAuthClient(connection, fixture, fixture.secondHumanId, "Public ID second", "AllProjects", publicClientId = publicClientId)
      }
      assertStatementViolation(databaseUrl, "oauth_client_organization_id_name_key") { connection =>
        val _ = insertOAuthClient(connection, fixture, fixture.humanId, "Repeated name", "AllProjects", status = "Revoked")
        val _ = insertOAuthClient(connection, fixture, fixture.secondHumanId, "Repeated name", "AllProjects")
      }
      assertStatementViolation(databaseUrl, "user_account_type_check") { connection =>
        insert(
          connection,
          """
            INSERT INTO public."user" (
              id, first_name, last_name, avatar, color, is_super_admin,
              created_at, modified_at, archived_at, account_type
            ) VALUES (?, 'Invalid', 'Account type', NULL, NULL, FALSE,
              CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Robot')
          """,
          UUID.randomUUID()
        )
      }
      assertStatementViolation(databaseUrl, "oauth_client_status_check") { connection =>
        val _ = insertOAuthClient(connection, fixture, fixture.humanId, "Bad status", "AllProjects", status = "Disabled")
      }
      assertStatementViolation(databaseUrl, "oauth_client_project_access_mode_check") { connection =>
        val _ = insertOAuthClient(connection, fixture, fixture.humanId, "Bad mode", "SomeProjects")
      }

      Seq((0L, 1L, 1L), (-1L, 1L, 1L), (1L, 0L, 1L), (1L, -1L, 1L), (1L, 1L, 0L), (1L, 1L, -1L)).foreach {
        case (secretVersion, version, policyVersion) =>
          assertStatementViolation(databaseUrl, "oauth_client_positive_versions_check") { connection =>
            val _ = insertOAuthClient(
              connection,
              fixture,
              fixture.humanId,
              s"Bad versions $secretVersion-$version-$policyVersion",
              "AllProjects",
              secretVersion = secretVersion,
              version = version,
              policyVersion = policyVersion
            )
          }
      }

      val clientId = UUID.randomUUID()
      assertStatementViolation(databaseUrl, "oauth_client_scope_scope_check") { connection =>
        insertScope(connection, clientId, "unknown:scope")
      }
      true should beTrue
    }

    "enforce non-empty scopes and coherent same-organization project policies at commit" in withMigratedDatabase { (databaseUrl, fixture) =>
      assertCommitViolation(databaseUrl, "oauth_client_scope_required_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val _ = insertOAuthClient(connection, fixture, serviceAccountId, "No scopes", "AllProjects")
      }

      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "No selected project", "SelectedProjects")
        insertScope(connection, clientId)
      }

      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "All with project", "AllProjects")
        insertScope(connection, clientId)
        insertOAuthProject(connection, clientId, fixture.projectId)
      }

      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        val serviceAccountId = UUID.randomUUID()
        insertServiceAccount(connection, serviceAccountId)
        insertMembership(connection, fixture.organizationId, serviceAccountId)
        val clientId = insertOAuthClient(connection, fixture, serviceAccountId, "Cross organization", "SelectedProjects")
        insertScope(connection, clientId)
        insertOAuthProject(connection, clientId, fixture.secondProjectId)
      }
      true should beTrue
    }

    "reject scope and project policy mutation or deletion paths only at commit" in withMigratedDatabase { (databaseUrl, fixture) =>
      val scopeDeletionUserId = UUID.randomUUID()
      val scopeDeletionClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, scopeDeletionUserId, "Delete last scope", "AllProjects", None)
      }
      assertCommitViolation(databaseUrl, "oauth_client_scope_required_check") { connection =>
        update(connection, "DELETE FROM public.oauth_client_scope WHERE oauth_client_id = ?", scopeDeletionClientId)
      }

      val projectDeletionUserId = UUID.randomUUID()
      val projectDeletionClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(
          connection,
          fixture,
          projectDeletionUserId,
          "Delete last selected project",
          "SelectedProjects",
          Some(fixture.projectId)
        )
      }
      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        update(connection, "DELETE FROM public.oauth_client_project WHERE oauth_client_id = ?", projectDeletionClientId)
      }

      val allToSelectedUserId = UUID.randomUUID()
      val allToSelectedClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, allToSelectedUserId, "All to selected", "AllProjects", None)
      }
      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET project_access_mode = 'SelectedProjects' WHERE id = ?", allToSelectedClientId)
      }

      val selectedToAllUserId = UUID.randomUUID()
      val selectedToAllClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, selectedToAllUserId, "Selected to all", "SelectedProjects", Some(fixture.projectId))
      }
      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET project_access_mode = 'AllProjects' WHERE id = ?", selectedToAllClientId)
      }
      true should beTrue
    }

    "reject organization and service-account reassignment mutation paths only at commit" in withMigratedDatabase { (databaseUrl, fixture) =>
      val membershipMismatchUserId = UUID.randomUUID()
      val membershipMismatchClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, membershipMismatchUserId, "Move client organization", "AllProjects", None)
      }
      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        update(
          connection,
          "UPDATE public.oauth_client SET organization_id = ? WHERE id = ?",
          fixture.secondOrganizationId,
          membershipMismatchClientId
        )
      }

      val projectMismatchUserId = UUID.randomUUID()
      val projectMismatchClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(
          connection,
          fixture,
          projectMismatchUserId,
          "Move selected client organization",
          "SelectedProjects",
          Some(fixture.projectId)
        )
      }
      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        update(
          connection,
          "UPDATE public.organization_user SET organization_id = ? WHERE user_id = ?",
          fixture.secondOrganizationId,
          projectMismatchUserId
        )
        update(connection, "UPDATE public.oauth_client SET organization_id = ? WHERE id = ?", fixture.secondOrganizationId, projectMismatchClientId)
      }

      val referencedProjectUserId = UUID.randomUUID()
      withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(
          connection,
          fixture,
          referencedProjectUserId,
          "Move referenced project organization",
          "SelectedProjects",
          Some(fixture.projectId)
        )
      }
      assertCommitViolation(databaseUrl, "oauth_client_project_policy_check") { connection =>
        update(connection, "UPDATE public.project SET organization_id = ? WHERE id = ?", fixture.secondOrganizationId, fixture.projectId)
      }

      val reassignedFromUserId = UUID.randomUUID()
      val reassignedClientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, reassignedFromUserId, "Reassign service account", "AllProjects", None)
      }
      assertCommitViolation(databaseUrl, "oauth_service_account_shape_check") { connection =>
        val reassignedToUserId = UUID.randomUUID()
        insertServiceAccount(connection, reassignedToUserId)
        insertMembership(connection, fixture.organizationId, reassignedToUserId)
        update(connection, "UPDATE public.oauth_client SET service_account_user_id = ? WHERE id = ?", reassignedToUserId, reassignedClientId)
      }
      true should beTrue
    }

    "declare the required FK indexes and deferred constraint machinery in PostgreSQL" in withMigratedDatabase { (databaseUrl, _) =>
      indexNames(databaseUrl) should contain(
        allOf(
          "oauth_client_organization_id_idx",
          "oauth_client_service_account_user_id_key",
          "oauth_client_created_by_idx",
          "oauth_client_modified_by_idx",
          "oauth_client_secret_rotated_by_idx",
          "oauth_client_revoked_by_idx",
          "oauth_client_scope_pkey",
          "oauth_client_project_pkey",
          "oauth_client_project_project_id_idx",
          "oauth_audit_event_organization_idx",
          "oauth_audit_event_client_idx",
          "oauth_audit_event_actor_user_id_idx",
          "organization_user_user_id_idx"
        )
      )

      val requiredConstraintTriggers = Set(
        "oauth_user_account_type_immutable_trigger",
        "oauth_user_service_account_shape_trigger",
        "oauth_client_service_account_shape_trigger",
        "oauth_organization_user_service_account_shape_trigger",
        "oauth_user_identity_service_account_trigger",
        "oauth_refresh_token_service_account_trigger",
        "oauth_client_policy_trigger",
        "oauth_client_scope_policy_trigger",
        "oauth_client_project_policy_trigger",
        "oauth_project_organization_policy_trigger",
        "oauth_client_human_actor_trigger"
      )
      val triggerProperties = userTriggerProperties(databaseUrl)
      requiredConstraintTriggers.foreach { triggerName =>
        triggerProperties.get(triggerName) should beSome((true, true, true))
      }
      deferredUniqueConstraint(databaseUrl, "oauth_client", "oauth_client_service_account_user_id_key") shouldEqual ("u", true, true)
    }

    "use paging indexes whose catalog order matches canonical descending audit queries" in withMigratedDatabase { (databaseUrl, fixture) =>
      indexDefinition(databaseUrl, "oauth_audit_event_organization_idx")
        .contains("(organization_id, occurred_at DESC, id DESC) WHERE (organization_id IS NOT NULL)") should beTrue
      indexDefinition(databaseUrl, "oauth_audit_event_client_idx")
        .contains("(oauth_client_id, occurred_at DESC, id DESC) WHERE (oauth_client_id IS NOT NULL)") should beTrue

      val organizationPlan = explainAuditPage(databaseUrl, "organization_id", fixture.organizationId)
      organizationPlan.contains("Index Scan using oauth_audit_event_organization_idx") should beTrue
      organizationPlan.contains("Sort") should beFalse

      val clientPlan = explainAuditPage(databaseUrl, "oauth_client_id", UUID.randomUUID())
      clientPlan.contains("Index Scan using oauth_audit_event_client_idx") should beTrue
      clientPlan.contains("Sort") should beFalse
    }

    "reject a secret sentinel stored under a neutral audit metadata key" in withMigratedDatabase { (databaseUrl, fixture) =>
      assertStatementViolation(databaseUrl, "oauth_audit_event_metadata_schema_check") { connection =>
        val _ = insertAuditEvent(connection, fixture, "Management", "Create", """{"value":"epoca_secret_plaintext"}""")
      }
      true should beTrue
    }

    "accept only closed event-specific typed audit metadata" in withMigratedDatabase { (databaseUrl, fixture) =>
      val projectId     = UUID.randomUUID()
      val allScopesJson = fixedScopes.map(scope => s"\"$scope\"").mkString(",")
      val acceptedMetadata = Seq(
        (
          "Management",
          "Create",
          s"""{"scopes":[$allScopesJson],"projectMode":"SelectedProjects","projectIds":["$projectId"],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "Management",
          "Create",
          s"""{"scopes":[$allScopesJson],"projectMode":"SelectedProjects","projectIds":["$projectId"],"version":1,"policyVersion":1,"secretVersion":1,"expiresAt":"2027-01-02T03:04:05.123456Z"}"""
        ),
        (
          "Management",
          "Create",
          s"""{"scopes":[$allScopesJson],"projectMode":"SelectedProjects","projectIds":["$projectId"],"version":1,"policyVersion":1,"secretVersion":1,"expiresAt":null}"""
        ),
        (
          "Management",
          "Update",
          s"""{"beforeScopes":["projects:read"],"afterScopes":["projects:read","reports:read"],"beforeProjectMode":"AllProjects","afterProjectMode":"SelectedProjects","beforeProjectIds":[],"afterProjectIds":["$projectId"],"beforeVersion":1,"afterVersion":2,"beforePolicyVersion":1,"afterPolicyVersion":2}"""
        ),
        (
          "Management",
          "Update",
          s"""{"beforeScopes":["projects:read"],"afterScopes":["projects:read","reports:read"],"beforeProjectMode":"AllProjects","afterProjectMode":"SelectedProjects","beforeProjectIds":[],"afterProjectIds":["$projectId"],"beforeVersion":1,"afterVersion":2,"beforePolicyVersion":1,"afterPolicyVersion":2,"beforeExpiresAt":null,"afterExpiresAt":"2027-01-02T03:04:05Z"}"""
        ),
        ("Management", "Rotate", """{"beforeVersion":2,"afterVersion":3,"beforeSecretVersion":1,"afterSecretVersion":2}"""),
        ("Management", "Revoke", """{"beforeVersion":3,"afterVersion":4,"beforePolicyVersion":2,"afterPolicyVersion":3}"""),
        (
          "Authentication",
          "TokenIssued",
          s"""{"scopes":["projects:read"],"projectMode":"SelectedProjects","projectIds":["$projectId"],"policyVersion":3,"secretVersion":2}"""
        ),
        ("Authentication", "InvalidClient", """{"failureCategory":"unknown-client"}"""),
        ("Authentication", "InvalidScope", """{"failureCategory":"ungranted-scope"}"""),
        ("Authentication", "RateLimit", """{"failureCategory":"client-rate-limit"}""")
      )
      withConnection(databaseUrl) { connection =>
        acceptedMetadata.foreach { case (category, eventType, metadata) =>
          insertAuditEvent(connection, fixture, category, eventType, metadata)
        }
        Seq(
          "InvalidClient" -> Seq("malformed-credentials", "unknown-client", "invalid-secret", "revoked-client", "expired-client"),
          "InvalidScope"  -> Seq("malformed-scope", "unknown-scope", "ungranted-scope"),
          "RateLimit"     -> Seq("ip-rate-limit", "client-rate-limit")
        ).foreach { case (eventType, failureCategories) =>
          failureCategories.foreach { failureCategory =>
            insertAuditEvent(connection, fixture, "Authentication", eventType, s"""{"failureCategory":"$failureCategory"}""")
          }
        }
      }

      val rejectedMetadata = Seq(
        (
          "unknown key",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"value":"epoca_secret_plaintext"}"""
        ),
        (
          "nested object",
          "Management",
          "Create",
          """{"scopes":{"nested":["projects:read"]},"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "scope type",
          "Management",
          "Create",
          """{"scopes":"projects:read","projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "scope value",
          "Management",
          "Create",
          """{"scopes":["admin:write"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "mode",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"SomeProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "project id",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"SelectedProjects","projectIds":["not-a-uuid"],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "zero version",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":0,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "fractional version",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1.5,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "string version",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":"1","policyVersion":1,"secretVersion":1}"""
        ),
        (
          "expiry key under wrong event",
          "Management",
          "Rotate",
          """{"beforeVersion":2,"afterVersion":3,"beforeSecretVersion":1,"afterSecretVersion":2,"expiresAt":null}"""
        ),
        (
          "update expiry key under create",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"beforeExpiresAt":null}"""
        ),
        (
          "malformed expiry timestamp",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"expiresAt":"tomorrow"}"""
        ),
        (
          "invalid expiry time-zone displacement",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"expiresAt":"2027-01-02T03:04:05+24:00"}"""
        ),
        (
          "numeric expiry timestamp",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"expiresAt":123}"""
        ),
        (
          "sensitive client secret",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"clientSecret":"secret"}"""
        ),
        (
          "sensitive secret hash",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"secretHash":"hash"}"""
        ),
        (
          "sensitive JWT material",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"JWT":"signed.jwt.value"}"""
        ),
        (
          "sensitive access token",
          "Management",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1,"accessToken":"token"}"""
        ),
        (
          "sensitive authorization material",
          "Management",
          "Update",
          s"""{"beforeScopes":["projects:read"],"afterScopes":["projects:read"],"beforeProjectMode":"AllProjects","afterProjectMode":"SelectedProjects","beforeProjectIds":[],"afterProjectIds":["$projectId"],"beforeVersion":1,"afterVersion":2,"beforePolicyVersion":1,"afterPolicyVersion":2,"Authorization":"Bearer token"}"""
        ),
        ("expired category under wrong event", "Authentication", "InvalidScope", """{"failureCategory":"expired-client"}"""),
        ("sentinel allowed key", "Authentication", "InvalidClient", """{"failureCategory":"epoca_secret_plaintext"}"""),
        ("wrong event key", "Authentication", "RateLimit", """{"failureCategory":"client-rate-limit","scopes":["projects:read"]}""")
      )
      rejectedMetadata.foreach { case (_, category, eventType, metadata) =>
        assertStatementViolation(databaseUrl, "oauth_audit_event_metadata_schema_check") { connection =>
          val _ = insertAuditEvent(connection, fixture, category, eventType, metadata)
        }
      }

      val rejectedEvents = Seq(
        (
          "Management",
          "TokenIssued",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "Authentication",
          "Create",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        ),
        (
          "Management",
          "Unknown",
          """{"scopes":["projects:read"],"projectMode":"AllProjects","projectIds":[],"version":1,"policyVersion":1,"secretVersion":1}"""
        )
      )
      rejectedEvents.foreach { case (category, eventType, metadata) =>
        assertStatementViolation(databaseUrl, "oauth_audit_event_category_type_check") { connection =>
          val _ = insertAuditEvent(connection, fixture, category, eventType, metadata)
        }
      }
      true should beTrue
    }

    "reject every audit event update" in withMigratedDatabase { (databaseUrl, fixture) =>
      val auditEventId = withConnection(databaseUrl) { connection =>
        insertAuditEvent(connection, fixture, "Management", "Create", validCreateAuditMetadata)
      }
      assertStatementViolation(databaseUrl, "oauth_audit_event_immutable_check") { connection =>
        update(connection, "UPDATE public.oauth_audit_event SET outcome = 'Failure' WHERE id = ?", auditEventId)
      }
      true should beTrue
    }

    "retain Management audit events indefinitely" in withMigratedDatabase { (databaseUrl, fixture) =>
      val auditEventId = withConnection(databaseUrl) { connection =>
        insertAuditEvent(connection, fixture, "Management", "Create", validCreateAuditMetadata, OffsetDateTime.now().minusDays(365))
      }
      assertStatementViolation(databaseUrl, "oauth_audit_event_retention_check") { connection =>
        update(connection, "DELETE FROM public.oauth_audit_event WHERE id = ?", auditEventId)
      }
      true should beTrue
    }

    "reject cleanup of Authentication audit events younger than 90 days" in withMigratedDatabase { (databaseUrl, fixture) =>
      val auditEventId = withConnection(databaseUrl) { connection =>
        insertAuditEvent(connection, fixture, "Authentication", "InvalidClient", validInvalidClientAuditMetadata, OffsetDateTime.now().minusDays(89))
      }
      assertStatementViolation(databaseUrl, "oauth_audit_event_retention_check") { connection =>
        update(connection, "DELETE FROM public.oauth_audit_event WHERE id = ?", auditEventId)
      }
      true should beTrue
    }

    "delete eligible Authentication audit events older than 90 days" in withMigratedDatabase { (databaseUrl, fixture) =>
      val auditEventId = withConnection(databaseUrl) { connection =>
        insertAuditEvent(connection, fixture, "Authentication", "InvalidClient", validInvalidClientAuditMetadata, OffsetDateTime.now().minusDays(91))
      }
      withTransaction(databaseUrl) { connection =>
        update(connection, "DELETE FROM public.oauth_audit_event WHERE id = ?", auditEventId)
      }
      scalarLong(databaseUrl, s"SELECT count(*) FROM public.oauth_audit_event WHERE id = '$auditEventId'") shouldEqual 0L
    }

    "roll back Authentication cleanup statements above the database batch bound" in withMigratedDatabase { (databaseUrl, _) =>
      withConnection(databaseUrl) { connection =>
        val statement = connection.createStatement()
        try {
          val inserted = statement.executeUpdate("""
              INSERT INTO public.oauth_audit_event (
                id, occurred_at, category, organization_id, oauth_client_id, actor_type, actor_user_id,
                event_type, outcome, correlation_id, token_jti, source_ip, metadata
              )
              SELECT md5('oauth-oversized-' || item)::uuid, CURRENT_TIMESTAMP - INTERVAL '91 days',
                'Authentication', NULL, NULL, 'Anonymous', NULL, 'InvalidClient', 'Failure', NULL, NULL, NULL,
                '{"failureCategory":"unknown-client"}'::jsonb
              FROM generate_series(1, 1001) item
            """)
          require(inserted == 1001, s"Expected 1001 audit rows, got $inserted")
        } finally statement.close()
      }
      assertStatementViolation(databaseUrl, "oauth_audit_event_cleanup_batch_check") { connection =>
        val deleted = executeUpdateCount(
          connection,
          "DELETE FROM public.oauth_audit_event WHERE category = 'Authentication' AND occurred_at < CURRENT_TIMESTAMP - INTERVAL '90 days'"
        )
        require(deleted == 1001, s"Expected 1001 deleted rows, got $deleted")
      }
      scalarLong(databaseUrl, "SELECT count(*) FROM public.oauth_audit_event") shouldEqual 1001L
    }

    "make Revoked OAuth clients terminal while allowing the initial revocation" in withMigratedDatabase { (databaseUrl, fixture) =>
      val serviceAccountId = UUID.randomUUID()
      val clientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "Terminal revoke", "AllProjects", None)
      }
      withTransaction(databaseUrl) { connection =>
        update(
          connection,
          "UPDATE public.oauth_client SET status = 'Revoked', revoked_at = CURRENT_TIMESTAMP, revoked_by = ? WHERE id = ?",
          fixture.humanId,
          clientId
        )
      }
      assertStatementViolation(databaseUrl, "oauth_client_revoked_terminal_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET status = 'Active', revoked_at = NULL, revoked_by = NULL WHERE id = ?", clientId)
      }
      assertStatementViolation(databaseUrl, "oauth_client_revoked_terminal_check") { connection =>
        update(connection, "UPDATE public.oauth_client SET name = 'Renamed revoked client' WHERE id = ?", clientId)
      }
      assertStatementViolation(databaseUrl, "oauth_client_revoked_terminal_check") { connection =>
        update(connection, "DELETE FROM public.oauth_client WHERE id = ?", clientId)
      }
      true should beTrue
    }

    "retain an Active OAuth client during an otherwise complete deferred cleanup" in withMigratedDatabase { (databaseUrl, fixture) =>
      val serviceAccountId = UUID.randomUUID()
      val clientId = withTransaction(databaseUrl) { connection =>
        createLegalServiceAccount(connection, fixture, serviceAccountId, "Retained active client", "AllProjects", None)
      }

      withConnection(databaseUrl) { connection =>
        connection.setAutoCommit(false)
        try
          Try {
            update(connection, "DELETE FROM public.oauth_client_scope WHERE oauth_client_id = ?", clientId)
            update(connection, "DELETE FROM public.oauth_client WHERE id = ?", clientId)
            update(connection, "DELETE FROM public.organization_user WHERE user_id = ?", serviceAccountId)
            update(connection, "DELETE FROM public.\"user\" WHERE id = ?", serviceAccountId)
            connection.commit()
          } match {
            case Failure(error: PSQLException) => assertConstraint(error, "oauth_client_revoked_terminal_check")
            case Failure(error) =>
              throw new AssertionError(s"Expected PostgreSQL client-retention violation, got ${error.getClass.getName}", error)
            case Success(_) => throw new AssertionError("Expected Active OAuth client deletion to be rejected, but cleanup committed")
          }
        finally {
          val _ = Try(connection.rollback())
        }
      }

      scalarLong(databaseUrl, s"SELECT count(*) FROM public.oauth_client WHERE id = '$clientId'") shouldEqual 1L
      scalarLong(databaseUrl, s"SELECT count(*) FROM public.\"user\" WHERE id = '$serviceAccountId'") shouldEqual 1L
    }

    "store closed safe audit metadata and shared rate-limit state" in withMigratedDatabase { (databaseUrl, fixture) =>
      withConnection(databaseUrl) { connection =>
        insertAuditEvent(connection, fixture, "Authentication", "InvalidClient", """{"failureCategory":"unknown-client"}""")
        insert(
          connection,
          """
              INSERT INTO public.oauth_rate_limit_bucket (
                dimension, bucket_key, available_tokens, last_refilled_at, expires_at
              ) VALUES ('Ip', ?, 59, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 minute')
            """,
          s"ip-${UUID.randomUUID()}"
        )
      }
      true should beTrue
    }
  }

  private def withMigratedDatabase[T](test: (String, FixtureIds) => T): T =
    withTemporaryDatabase { databaseUrl =>
      configuredFlyway(databaseUrl, Some(MigrationVersion.fromVersion("31"))).migrate()
      test(databaseUrl, seedFixture(databaseUrl))
    }

  private def configuredFlyway(databaseUrl: String, target: Option[MigrationVersion]): Flyway = {
    val configuration = Flyway
      .configure()
      .dataSource(databaseUrl, databaseUser, databasePassword)
      .locations(migrationLocation)
      .schemas("public", "quartz")

    target.fold(configuration.load())(version => configuration.target(version).load())
  }

  private def seedFixture(databaseUrl: String): FixtureIds = {
    val fixture = FixtureIds(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    withTransaction(databaseUrl) { connection =>
      insertUser(connection, fixture.humanId)
      insertUser(connection, fixture.secondHumanId)
      insertOrganization(connection, fixture.organizationId, fixture.humanId, "Primary")
      insertOrganization(connection, fixture.secondOrganizationId, fixture.humanId, "Secondary")
      insertBusinessClientAndProject(connection, fixture.organizationId, fixture.projectId, fixture.humanId, "Primary")
      insertBusinessClientAndProject(connection, fixture.secondOrganizationId, fixture.secondProjectId, fixture.humanId, "Secondary")
    }
    fixture
  }

  private def insertUser(databaseUrl: String, userId: UUID): Unit =
    withConnection(databaseUrl)(connection => insertUser(connection, userId))

  private def insertUser(connection: Connection, userId: UUID): Unit =
    insert(
      connection,
      """
        INSERT INTO public."user" (
          id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at
        ) VALUES (?, 'Migration', 'Human', NULL, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
      """,
      userId
    )

  private def insertServiceAccount(connection: Connection, userId: UUID): Unit =
    insert(
      connection,
      """
        INSERT INTO public."user" (
          id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at, account_type
        ) VALUES (?, 'Integration', 'Migration client', NULL, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'ServiceAccount')
      """,
      userId
    )

  private def insertOrganization(
    connection: Connection,
    organizationId: UUID,
    actorId: UUID,
    label: String
  ): Unit =
    insert(
      connection,
      """
        INSERT INTO public.organization (
          id, name, slug, created_at, created_by, modified_at, modified_by, archived_at
        ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL)
      """,
      organizationId,
      s"$label migration organization",
      s"oauth-${label.toLowerCase}-${organizationId.toString}",
      actorId,
      actorId
    )

  private def insertBusinessClientAndProject(
    connection: Connection,
    organizationId: UUID,
    projectId: UUID,
    actorId: UUID,
    label: String
  ): Unit = {
    val businessClientId = UUID.randomUUID()
    insert(
      connection,
      """
        INSERT INTO public.client (
          id, name, color, organization_id, created_at, created_by, modified_at,
          modified_by, archived_at, archived_by, logo, type, texture
        ) VALUES (?, ?, '#000000', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL, '', 'External', '')
      """,
      businessClientId,
      s"$label migration client",
      organizationId,
      actorId,
      actorId
    )
    insert(
      connection,
      """
        INSERT INTO public.project (
          id, name, description, organization_id, client_id, created_at, created_by,
          modified_at, modified_by, archived_at, archived_by
        ) VALUES (?, ?, NULL, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, NULL, NULL)
      """,
      projectId,
      s"$label migration project",
      organizationId,
      businessClientId,
      actorId,
      actorId
    )
  }

  private def createLegalServiceAccount(
    connection: Connection,
    fixture: FixtureIds,
    serviceAccountId: UUID,
    name: String,
    mode: String,
    projectId: Option[UUID]
  ): UUID = {
    insertServiceAccount(connection, serviceAccountId)
    insertMembership(connection, fixture.organizationId, serviceAccountId)
    val clientId = insertOAuthClient(connection, fixture, serviceAccountId, name, mode)
    insertScope(connection, clientId)
    projectId.foreach(insertOAuthProject(connection, clientId, _))
    clientId
  }

  private def insertMembership(
    connection: Connection,
    organizationId: UUID,
    userId: UUID,
    roles: Seq[String] = Seq.empty
  ): Unit = {
    val rolesLiteral = roles.map(role => s"\"${role.replace("\"", "\"\"")}\"").mkString("{", ",", "}")
    insert(
      connection,
      "INSERT INTO public.organization_user (organization_id, user_id, roles) VALUES (?, ?, ?::text[])",
      organizationId,
      userId,
      rolesLiteral
    )
  }

  private def insertOAuthClient(
    connection: Connection,
    fixture: FixtureIds,
    serviceAccountId: UUID,
    name: String,
    mode: String,
    status: String = "Active",
    publicClientId: String = s"epoca_${UUID.randomUUID().toString.replace("-", "")}",
    secretVersion: Long = 1L,
    version: Long = 1L,
    policyVersion: Long = 1L
  ): UUID = {
    val clientId  = UUID.randomUUID()
    val revokedAt = if (status == "Revoked") OffsetDateTime.now() else null
    val revokedBy = if (status == "Revoked") fixture.humanId else null
    insert(
      connection,
      """
        INSERT INTO public.oauth_client (
          id, client_id, organization_id, service_account_user_id, name, status,
          project_access_mode, secret_hash, secret_version, version, policy_version,
          created_at, created_by, modified_at, modified_by, secret_rotated_at,
          secret_rotated_by, revoked_at, revoked_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, '$2a$12$migration.test.hash', ?, ?, ?,
          CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, ?, ?)
      """,
      clientId,
      publicClientId,
      fixture.organizationId,
      serviceAccountId,
      name,
      status,
      mode,
      java.lang.Long.valueOf(secretVersion),
      java.lang.Long.valueOf(version),
      java.lang.Long.valueOf(policyVersion),
      fixture.humanId,
      fixture.humanId,
      fixture.humanId,
      revokedAt,
      revokedBy
    )
    clientId
  }

  private def insertScope(
    connection: Connection,
    clientId: UUID,
    scope: String = fixedScopes.head
  ): Unit =
    insert(connection, "INSERT INTO public.oauth_client_scope (oauth_client_id, scope) VALUES (?, ?)", clientId, scope)

  private def insertOAuthProject(
    connection: Connection,
    clientId: UUID,
    projectId: UUID
  ): Unit =
    insert(connection, "INSERT INTO public.oauth_client_project (oauth_client_id, project_id) VALUES (?, ?)", clientId, projectId)

  private def insertAuditEvent(
    connection: Connection,
    fixture: FixtureIds,
    category: String,
    eventType: String,
    metadata: String,
    occurredAt: OffsetDateTime = OffsetDateTime.now()
  ): UUID = {
    val auditEventId = UUID.randomUUID()
    val actorType    = if (category == "Management") "Human" else "Anonymous"
    val actorUserId  = if (category == "Management") fixture.humanId else null
    val outcome      = if (category == "Management" || eventType == "TokenIssued") "Success" else "Failure"
    insert(
      connection,
      """
        INSERT INTO public.oauth_audit_event (
          id, occurred_at, category, organization_id, oauth_client_id, actor_type, actor_user_id,
          event_type, outcome, correlation_id, token_jti, source_ip, metadata
        ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, NULL, ?::jsonb)
      """,
      auditEventId,
      occurredAt,
      category,
      fixture.organizationId,
      actorType,
      actorUserId,
      eventType,
      outcome,
      s"correlation-${UUID.randomUUID()}",
      metadata
    )
    auditEventId
  }

  private def accountType(databaseUrl: String, userId: UUID): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT account_type FROM public.\"user\" WHERE id = ?")
      try {
        statement.setObject(1, userId)
        singleResult(statement.executeQuery())(_.getString(1))
      } finally statement.close()
    }

  private def existingTables(databaseUrl: String): Set[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT table_name
          FROM information_schema.tables
          WHERE table_schema = 'public' AND table_name LIKE 'oauth_%'
        """)
      try rows(statement.executeQuery())(_.getString(1)).toSet
      finally statement.close()
    }

  private def columnNames(databaseUrl: String, tableName: String): Set[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT column_name
          FROM information_schema.columns
          WHERE table_schema = 'public' AND table_name = ?
        """)
      try {
        statement.setString(1, tableName)
        rows(statement.executeQuery())(_.getString(1)).toSet
      } finally statement.close()
    }

  private def oauthClientLifecycle(databaseUrl: String, clientId: UUID): (Option[OffsetDateTime], Option[OffsetDateTime]) =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT expires_at, last_used_at FROM public.oauth_client WHERE id = ?")
      try {
        statement.setObject(1, clientId)
        singleResult(statement.executeQuery()) { row =>
          Option(row.getObject(1, classOf[OffsetDateTime])) -> Option(row.getObject(2, classOf[OffsetDateTime]))
        }
      } finally statement.close()
    }

  private def oauthClientAdministrativeTimestamps(databaseUrl: String, clientId: UUID)
    : (OffsetDateTime, Option[OffsetDateTime], Option[OffsetDateTime]) =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT created_at, expires_at, last_used_at FROM public.oauth_client WHERE id = ?")
      try {
        statement.setObject(1, clientId)
        singleResult(statement.executeQuery()) { row =>
          (
            row.getObject(1, classOf[OffsetDateTime]),
            Option(row.getObject(2, classOf[OffsetDateTime])),
            Option(row.getObject(3, classOf[OffsetDateTime]))
          )
        }
      } finally statement.close()
    }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  private def indexNames(databaseUrl: String): Set[String] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
      try rows(statement.executeQuery())(_.getString(1)).toSet
      finally statement.close()
    }

  private def indexDefinition(databaseUrl: String, indexName: String): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")
      try {
        statement.setString(1, indexName)
        singleResult(statement.executeQuery())(_.getString(1))
      } finally statement.close()
    }

  private def explainAuditPage(
    databaseUrl: String,
    filterColumn: String,
    filterId: UUID
  ): String = {
    require(Set("organization_id", "oauth_client_id").contains(filterColumn), s"Unexpected audit filter column: $filterColumn")
    withConnection(databaseUrl) { connection =>
      val disableSeqScan = connection.createStatement()
      try disableSeqScan.execute("SET enable_seqscan = off")
      finally disableSeqScan.close()

      val statement = connection.prepareStatement(
        s"EXPLAIN (COSTS OFF) SELECT * FROM public.oauth_audit_event WHERE $filterColumn = ? ORDER BY occurred_at DESC, id DESC LIMIT 50"
      )
      try {
        statement.setObject(1, filterId)
        rows(statement.executeQuery())(_.getString(1)).mkString("\n")
      } finally statement.close()
    }
  }

  private def userTriggerProperties(databaseUrl: String): Map[String, (Boolean, Boolean, Boolean)] =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT trigger.tgname, trigger.tgconstraint <> 0, trigger.tgdeferrable, trigger.tginitdeferred
          FROM pg_trigger trigger
          JOIN pg_class relation ON relation.oid = trigger.tgrelid
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
          WHERE namespace.nspname = 'public' AND NOT trigger.tgisinternal
        """)
      try rows(statement.executeQuery())(row => row.getString(1) -> ((row.getBoolean(2), row.getBoolean(3), row.getBoolean(4)))).toMap
      finally statement.close()
    }

  private def deferredUniqueConstraint(
    databaseUrl: String,
    tableName: String,
    constraintName: String
  ): (String, Boolean, Boolean) =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT schema_constraint.contype::text, schema_constraint.condeferrable, schema_constraint.condeferred
          FROM pg_constraint schema_constraint
          JOIN pg_class relation ON relation.oid = schema_constraint.conrelid
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
          WHERE namespace.nspname = 'public' AND relation.relname = ? AND schema_constraint.conname = ?
        """)
      try {
        statement.setString(1, tableName)
        statement.setString(2, constraintName)
        singleResult(statement.executeQuery())(row => (row.getString(1), row.getBoolean(2), row.getBoolean(3)))
      } finally statement.close()
    }

  private def flywayCurrentVersion(databaseUrl: String): String =
    withConnection(databaseUrl) { connection =>
      val statement = connection.prepareStatement("""
          SELECT version
          FROM public.flyway_schema_history
          WHERE success AND version IS NOT NULL
          ORDER BY installed_rank DESC
          LIMIT 1
        """)
      try singleResult(statement.executeQuery())(_.getString(1))
      finally statement.close()
    }

  private def scalarLong(databaseUrl: String, sql: String): Long =
    withConnection(databaseUrl) { connection =>
      val statement = connection.createStatement()
      try singleResult(statement.executeQuery(sql))(_.getLong(1))
      finally statement.close()
    }

  private def assertCommitViolation(databaseUrl: String, expectedConstraint: String)(action: Connection => Unit): Unit =
    withConnection(databaseUrl) { connection =>
      connection.setAutoCommit(false)
      try {
        action(connection)
        Try(connection.commit()) match {
          case Failure(error: PSQLException) => assertConstraint(error, expectedConstraint)
          case Failure(error) =>
            throw new AssertionError(s"Expected PostgreSQL constraint violation at commit, got ${error.getClass.getName}", error)
          case Success(_) => throw new AssertionError("Expected PostgreSQL constraint violation at commit, but commit succeeded")
        }
      } finally {
        val _ = Try(connection.rollback())
      }
    }

  private def assertStatementViolation(databaseUrl: String, expectedConstraint: String)(action: Connection => Unit): Unit =
    withConnection(databaseUrl) { connection =>
      connection.setAutoCommit(false)
      try
        Try(action(connection)) match {
          case Failure(error: PSQLException) => assertConstraint(error, expectedConstraint)
          case Failure(error) =>
            throw new AssertionError(s"Expected PostgreSQL statement constraint violation, got ${error.getClass.getName}", error)
          case Success(_) => throw new AssertionError("Expected PostgreSQL statement constraint violation, but write succeeded")
        }
      finally {
        val _ = Try(connection.rollback())
      }
    }

  private def assertConstraint(error: PSQLException, expectedConstraint: String): Unit = {
    require(Set("23505", "23514").contains(error.getSQLState), s"Expected constraint SQLSTATE, got ${error.getSQLState}")
    require(
      Option(error.getServerErrorMessage).map(_.getConstraint).contains(expectedConstraint),
      s"Expected constraint $expectedConstraint, got ${Option(error.getServerErrorMessage).map(_.getConstraint)}: ${error.getMessage}"
    )
  }

  private def withTransaction[T](databaseUrl: String)(action: Connection => T): T =
    withConnection(databaseUrl) { connection =>
      connection.setAutoCommit(false)
      try {
        val result = action(connection)
        connection.commit()
        result
      } catch {
        case NonFatal(error) =>
          connection.rollback()
          throw error
      }
    }

  private def insert(
    connection: Connection,
    sql: String,
    parameters: AnyRef*
  ): Unit = {
    val statement = connection.prepareStatement(sql)
    try {
      parameters.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      require(statement.executeUpdate() == 1, "Expected one inserted row")
    } finally statement.close()
  }

  private def update(
    connection: Connection,
    sql: String,
    parameters: AnyRef*
  ): Unit = {
    val statement = connection.prepareStatement(sql)
    try {
      parameters.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
      require(statement.executeUpdate() == 1, "Expected one updated row")
    } finally statement.close()
  }

  private def executeUpdateCount(connection: Connection, sql: String): Int = {
    val statement = connection.prepareStatement(sql)
    try statement.executeUpdate()
    finally statement.close()
  }

  private def singleResult[T](resultSet: ResultSet)(read: ResultSet => T): T =
    try {
      require(resultSet.next(), "Expected one result row")
      val value = read(resultSet)
      require(!resultSet.next(), "Expected exactly one result row")
      value
    } finally resultSet.close()

  private def rows[T](resultSet: ResultSet)(read: ResultSet => T): List[T] =
    try {
      val result = List.newBuilder[T]
      while (resultSet.next()) result += read(resultSet)
      result.result()
    } finally resultSet.close()

  private def withTemporaryDatabase[T](test: String => T): T = {
    val databaseName = s"oauth_migration_${UUID.randomUUID().toString.replace("-", "")}"
    val identifier   = validatedDatabaseIdentifier(databaseName)
    var created      = false

    try {
      executeAdminStatement(s"CREATE DATABASE $identifier")
      created = true
      test(databaseUrl(databaseName))
    } finally
      if (created) executeAdminStatement(s"DROP DATABASE $identifier WITH (FORCE)")
  }

  private def validatedDatabaseIdentifier(databaseName: String): String = {
    require(databaseName.matches("^oauth_migration_[0-9a-f]{32}$"), s"Unsafe temporary database name: $databaseName")
    s"\"$databaseName\""
  }

  private def databaseUrl(databaseName: String): String = {
    require(baseDatabaseUrl.startsWith("jdbc:"), s"Unsupported TEST_DB_URL: $baseDatabaseUrl")
    val baseUri = URI.create(baseDatabaseUrl.stripPrefix("jdbc:"))
    require(baseUri.getHost != null, s"Unsupported TEST_DB_URL: $baseDatabaseUrl")
    val databaseUri =
      new URI(baseUri.getScheme, baseUri.getUserInfo, baseUri.getHost, baseUri.getPort, s"/$databaseName", baseUri.getQuery, baseUri.getFragment)
    s"jdbc:$databaseUri"
  }

  private def executeAdminStatement(sql: String): Unit =
    withConnection(baseDatabaseUrl) { connection =>
      val statement = connection.createStatement()
      try {
        val _ = statement.executeUpdate(sql)
      } finally statement.close()
    }

  private def withConnection[T](databaseUrl: String)(use: Connection => T): T = {
    val connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)
    try use(connection)
    finally connection.close()
  }
}
