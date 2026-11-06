package pl.iterators.epoca.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import io.swagger.v3.core.util.Yaml
import org.apache.pekko.http.scaladsl.model.HttpMethods
import pl.iterators.epoca.oauth.policy.{OAuthHttpOperation, OAuthOperationRegistry}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters._

object OpenApiContractPostProcessor {
  val CommitSha: String               = "0123456789abcdef0123456789abcdef01234567"
  val ChangedAt: String               = "2026-08-06T10:15:30Z"
  val OverrideProjectIds: Seq[String] = Seq("11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111112")
  val OverrideClientIds: Seq[String]  = Seq("22222222-2222-4222-8222-222222222221", "22222222-2222-4222-8222-222222222222")
  val PivotProjectIds: Seq[String]    = Seq("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
  val PivotUserIds: Seq[String]       = Seq("33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
  val PivotClientIds: Seq[String]     = Seq("55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666")
  val BuildInfoSchemaRef: String =
    "#/components/schemas/pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo"
  val PivotRequestSchemaRef: String            = "#/components/schemas/WorkUnitPivotQueryRequest"
  val PivotResponseSchemaRef: String           = "#/components/schemas/WorkUnitPivotQueryResponse"
  val PivotInvalidErrorSchemaRef: String       = "#/components/schemas/WorkUnitPivotInvalidConfigurationError"
  val PivotTooLargeErrorSchemaRef: String      = "#/components/schemas/WorkUnitPivotResultTooLargeError"
  val PivotProblemDetailsSchemaRef: String     = "#/components/schemas/WorkUnitPivotProblemDetails"
  val PivotPublishedSchemaPath: Path           = Path.of("docs/contracts/work-unit-pivot-query.schema.json")
  val PivotCanonicalResponsePath: Path         = Path.of("docs/contracts/work-unit-pivot-query-canonical-response.json")
  val PivotExportRequestSchemaRef: String      = "#/components/schemas/WorkUnitPivotExportRequest"
  val PivotExportProjectionSchemaRef: String   = "#/components/schemas/WorkUnitPivotExportProjection"
  val PivotExportPresentationSchemaRef: String = "#/components/schemas/WorkUnitPivotExportPresentation"
  val PivotInvalidExportErrorSchemaRef: String = "#/components/schemas/WorkUnitPivotInvalidExportRequest"

  private val ActorId                          = "33333333-3333-4333-8333-333333333333"
  private val CurrentPath                      = "/organizations/{organizationSlug}/work-units/{workUnitId}/override"
  private val HistoryPath                      = "/organizations/{organizationSlug}/work-units/{workUnitId}/override-history"
  private val UndoPath                         = s"$CurrentPath/undo"
  private val ReportPath                       = "/organizations/{organizationSlug}/work-units"
  private val PivotPath                        = "/organizations/{organizationSlug}/pivot-tables/work-units/query"
  private val PivotExportPath                  = "/organizations/{organizationSlug}/pivot-tables/work-units/export"
  private val PivotResponseStatuses            = Seq("200", "400", "401", "403", "404", "422")
  private val PivotExportResponseStatuses      = Seq("200", "204", "400", "401", "403", "404", "413", "422", "503")
  private val BuildInfoPath                    = "/build-info"
  private val BuildInfoSchemaName              = BuildInfoSchemaRef.stripPrefix("#/components/schemas/")
  private val GeneratedPivotRequestSchemaName  = "pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request"
  private val GeneratedPivotResponseSchemaName = "pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response"
  private val OAuthCollectionPath              = "/organizations/{organizationSlug}/oauth-clients"
  private val OAuthClientPath                  = s"$OAuthCollectionPath/{clientId}"
  private val OAuthViewSchemaName              = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView"
  private val OAuthSecretSchemaName            = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientSecretView"
  private val OAuthCreateSchemaName            = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientCreateRequest"
  private val OAuthUpdateSchemaName            = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientUpdateRequest"
  private val OAuthVersionSchemaName           = "pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientVersionRequest"
  private val OAuthClientPageSchemaName =
    "pl.iterators.epoca.utils.db.PaginatedResult_pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientView"
  private val OAuthAuditPageSchemaName =
    "pl.iterators.epoca.utils.db.PaginatedResult_pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthAuditEventView"
  private val OAuthScopeValues =
    Seq("organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export")
  private val OAuthProblemSchemaName           = "OAuthClientProblemDetails"
  private val OAuthStatusValues                = Seq("Active", "Expired", "Revoked")
  private val OAuthContractPostprocessedMarker = "x-epoca-oauth-admin-contract-postprocessed"
  private val RawOAuthViewFields = Set(
    "clientId",
    "name",
    "status",
    "scopes",
    "projectAccess",
    "version",
    "policyVersion",
    "secretVersion",
    "serviceAccount",
    "createdAt",
    "createdBy",
    "modifiedAt",
    "modifiedBy",
    "secretRotatedAt",
    "secretRotatedBy",
    "expiresAt",
    "revokedAt",
    "revokedBy",
    "lastUsedAt"
  )
  private val RawOAuthRequiredViewFields =
    RawOAuthViewFields -- Set("expiresAt", "revokedAt", "revokedBy", "lastUsedAt")
  private val RawOAuthAuditFields         = Set("id", "occurredAt", "actorType", "actorUserId", "actor", "eventType", "outcome", "metadata")
  private val RawOAuthRequiredAuditFields = Set("id", "occurredAt", "actorType", "eventType", "outcome", "metadata")
  private val RawOAuthAuditActorTypes     = Set("Anonymous", "Human", "ServiceAccount", "System")
  private val RawOAuthAuditEventTypes     = Set("RateLimit", "Update", "InvalidScope", "TokenIssued", "Revoke", "Rotate", "InvalidClient", "Create")
  private val OAuthAllProjectsRequestSchemaJson =
    """{
        "type": "object",
        "required": ["mode"],
        "properties": { "mode": { "type": "string", "enum": ["AllProjects"] } },
        "not": { "required": ["projectIds"] }
      }"""
  private val OAuthSelectedProjectsRequestSchemaJson =
    """{
        "type": "object",
        "required": ["mode", "projectIds"],
        "properties": {
          "mode": { "type": "string", "enum": ["SelectedProjects"] },
          "projectIds": { "type": "array", "minItems": 1, "uniqueItems": true, "items": { "type": "string", "format": "uuid" } }
        }
      }"""
  private val OAuthRequestProjectAccessAlternativesJson =
    s"""[$OAuthAllProjectsRequestSchemaJson, $OAuthSelectedProjectsRequestSchemaJson]"""
  private val OAuthNullableRequestProjectAccessAlternativesJson =
    s"""[
      { "type": "object", "nullable": true, "enum": [null] },
      $OAuthAllProjectsRequestSchemaJson,
      $OAuthSelectedProjectsRequestSchemaJson
    ]"""
  private val OAuthComponentSchemasTemplate =
    s"""{
      "$OAuthCreateSchemaName": {
        "type": "object",
        "required": ["name", "scopes", "projectAccess"],
        "properties": {
          "name": { "type": "string" },
          "scopes": {
            "type": "array",
            "minItems": 1,
            "uniqueItems": true,
            "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] }
          },
          "projectAccess": {
            "type": "object",
            "oneOf": $OAuthRequestProjectAccessAlternativesJson
          },
          "expiresAt": {
            "type": "string",
            "format": "date-time",
            "nullable": true,
            "description": "Optional ISO-8601 expiration with UTC or a numeric offset. Omit or send null for a client without expiration; accepted offsets are normalized to UTC and the instant must be strictly in the future according to database time."
          }
        }
      },
      "$OAuthUpdateSchemaName": {
        "type": "object",
        "required": ["expectedVersion"],
        "properties": {
          "expectedVersion": { "type": "integer", "format": "int64" },
          "name": { "type": "string", "nullable": true },
          "scopes": {
            "type": "array",
            "nullable": true,
            "minItems": 1,
            "uniqueItems": true,
            "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] }
          },
          "projectAccess": {
            "oneOf": $OAuthNullableRequestProjectAccessAlternativesJson
          },
          "expiresAt": {
            "type": "string",
            "format": "date-time",
            "nullable": true,
            "description": "Tri-state ISO-8601 expiration patch. Omit expiresAt to keep the current value, send explicit null to clear it, or send a date-time with UTC or a numeric offset to set it; accepted offsets are normalized to UTC."
          }
        }
      },
      "$OAuthVersionSchemaName": {
        "type": "object",
        "required": ["expectedVersion"],
        "properties": { "expectedVersion": { "type": "integer", "format": "int64" } }
      },
      "$OAuthViewSchemaName": {
        "type": "object",
        "additionalProperties": false,
        "required": ["clientId", "name", "status", "scopes", "projectAccess", "version", "policyVersion", "secretVersion", "serviceAccount", "createdAt", "createdBy", "modifiedAt", "modifiedBy", "secretRotatedAt", "secretRotatedBy", "expiresAt", "revokedAt", "revokedBy", "lastUsedAt"],
        "properties": {
          "clientId": { "type": "string" },
          "name": { "type": "string" },
          "status": { "type": "string", "enum": ["Active", "Expired", "Revoked"] },
          "scopes": {
            "type": "array",
            "uniqueItems": true,
            "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] }
          },
          "projectAccess": {
            "type": "object",
            "additionalProperties": false,
            "required": ["mode"],
            "properties": {
              "mode": { "type": "string", "enum": ["AllProjects", "SelectedProjects"] },
              "projectIds": { "type": "array", "uniqueItems": true, "items": { "type": "string", "format": "uuid" } }
            }
          },
          "version": { "type": "integer", "format": "int64" },
          "policyVersion": { "type": "integer", "format": "int64" },
          "secretVersion": { "type": "integer", "format": "int64" },
          "serviceAccount": { "type": "object", "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
          "createdAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$" },
          "createdBy": { "type": "object", "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
          "modifiedAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$" },
          "modifiedBy": { "type": "object", "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
          "secretRotatedAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$" },
          "secretRotatedBy": { "type": "object", "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
          "expiresAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true },
          "revokedAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true },
          "revokedBy": { "type": "object", "nullable": true, "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
          "lastUsedAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true }
        }
      },
      "$OAuthSecretSchemaName": {
        "type": "object",
        "additionalProperties": false,
        "required": ["client", "clientSecret"],
        "properties": {
          "client": { "REF": "#/components/schemas/$OAuthViewSchemaName" },
          "clientSecret": { "type": "string", "readOnly": true, "description": "Returned exactly once by create and rotate-secret responses." }
        }
      },
      "$OAuthClientPageSchemaName": {
        "type": "object",
        "additionalProperties": false,
        "required": ["count", "results"],
        "properties": {
          "count": { "type": "integer", "format": "int32" },
          "results": { "type": "array", "items": { "REF": "#/components/schemas/$OAuthViewSchemaName" } }
        }
      },
      "$OAuthAuditPageSchemaName": {
        "type": "object",
        "additionalProperties": false,
        "required": ["count", "results"],
        "properties": {
          "count": { "type": "integer", "format": "int32" },
          "results": {
            "type": "array",
            "items": {
              "type": "object",
              "additionalProperties": false,
              "required": ["id", "occurredAt", "actorType", "actorUserId", "actor", "eventType", "outcome", "metadata"],
              "properties": {
                "id": { "type": "string", "format": "uuid" },
                "occurredAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$" },
                "actorType": { "type": "string", "enum": ["Human", "ServiceAccount", "Anonymous", "System"] },
                "actorUserId": { "type": "string", "format": "uuid", "nullable": true },
                "actor": { "type": "object", "nullable": true, "additionalProperties": false, "required": ["userId", "displayName"], "properties": { "userId": { "type": "string", "format": "uuid" }, "displayName": { "type": "string" } } },
                "eventType": { "type": "string", "enum": ["Create", "Update", "Rotate", "Revoke", "TokenIssued", "InvalidClient", "InvalidScope", "RateLimit"] },
                "outcome": { "type": "string", "enum": ["Success", "Failure"] },
                "metadata": {
                  "type": "object",
                  "additionalProperties": false,
                  "description": "Closed metadata key set; valid combinations depend on eventType and are enforced by persistence.",
                  "properties": {
                    "scopes": { "type": "array", "uniqueItems": true, "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] } },
                    "beforeScopes": { "type": "array", "uniqueItems": true, "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] } },
                    "afterScopes": { "type": "array", "uniqueItems": true, "items": { "type": "string", "enum": ["organization:read", "users:read", "clients:read", "projects:read", "work-units:read", "work-units:write", "reports:read", "reports:export"] } },
                    "projectMode": { "type": "string", "enum": ["AllProjects", "SelectedProjects"] },
                    "beforeProjectMode": { "type": "string", "enum": ["AllProjects", "SelectedProjects"] },
                    "afterProjectMode": { "type": "string", "enum": ["AllProjects", "SelectedProjects"] },
                    "projectIds": { "type": "array", "uniqueItems": true, "items": { "type": "string", "format": "uuid" } },
                    "beforeProjectIds": { "type": "array", "uniqueItems": true, "items": { "type": "string", "format": "uuid" } },
                    "afterProjectIds": { "type": "array", "uniqueItems": true, "items": { "type": "string", "format": "uuid" } },
                    "version": { "type": "integer", "format": "int64", "minimum": 1 },
                    "policyVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "secretVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "beforeVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "afterVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "beforePolicyVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "afterPolicyVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "beforeSecretVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "afterSecretVersion": { "type": "integer", "format": "int64", "minimum": 1 },
                    "expiresAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true },
                    "beforeExpiresAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true },
                    "afterExpiresAt": { "type": "string", "format": "date-time", "pattern": "^.*Z$$", "nullable": true },
                    "failureCategory": { "type": "string", "enum": ["malformed-credentials", "unknown-client", "invalid-secret", "revoked-client", "expired-client", "malformed-scope", "unknown-scope", "ungranted-scope", "ip-rate-limit", "client-rate-limit"] }
                  }
                }
              }
            }
          }
        }
      },
      "$OAuthProblemSchemaName": {
        "type": "object",
        "additionalProperties": false,
        "required": ["type", "status", "title", "instance"],
        "properties": {
          "type": { "type": "string", "enum": ["result:oauth-client:invalid-expiration", "result:oauth-client:invalid-policy", "result:oauth-client:name-conflict", "result:oauth-client:stale-version", "result:oauth-client:not-active"] },
          "status": { "type": "integer", "format": "int32", "enum": [400, 409] },
          "title": { "type": "string" },
          "instance": { "type": "string", "format": "uri" },
          "extension": { "type": "object", "additionalProperties": false, "required": ["currentVersion"], "properties": { "currentVersion": { "type": "integer", "format": "int64" } } }
        }
      }
    }"""
  private lazy val OAuthComponentSchemasJson = OAuthComponentSchemasTemplate.replace("\"REF\"", "\"" + "$" + "ref\"")
  private val OAuthExpiredClientExampleJson =
    """{"clientId":"epoca_oauth_client_v1_contract","name":"Reporting warehouse","status":"Expired","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects"},"version":4,"policyVersion":3,"secretVersion":2,"serviceAccount":{"userId":"22222222-2222-4222-8222-222222222222","displayName":"Integration Reporting warehouse"},"createdAt":"2026-08-01T08:12:30Z","createdBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"modifiedAt":"2026-08-19T11:42:10Z","modifiedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"secretRotatedAt":"2026-08-10T09:00:00Z","secretRotatedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"expiresAt":"2026-08-18T11:00:00Z","revokedAt":null,"revokedBy":null,"lastUsedAt":null}"""
  private val OAuthCreatedClientExampleJson =
    """{"clientId":"epoca_oauth_client_v1_created","name":"Reporting warehouse","status":"Active","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects"},"version":1,"policyVersion":1,"secretVersion":1,"serviceAccount":{"userId":"22222222-2222-4222-8222-222222222222","displayName":"Integration Reporting warehouse"},"createdAt":"2026-08-20T08:00:00Z","createdBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"modifiedAt":"2026-08-20T08:00:00Z","modifiedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"secretRotatedAt":"2026-08-20T08:00:00Z","secretRotatedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"expiresAt":"2026-12-31T22:59:00Z","revokedAt":null,"revokedBy":null,"lastUsedAt":null}"""
  private val OAuthUpdatedClientExampleJson =
    """{"clientId":"epoca_oauth_client_v1_updated","name":"Reporting warehouse renewed","status":"Active","scopes":["projects:read","reports:read"],"projectAccess":{"mode":"AllProjects"},"version":5,"policyVersion":4,"secretVersion":2,"serviceAccount":{"userId":"22222222-2222-4222-8222-222222222222","displayName":"Integration Reporting warehouse renewed"},"createdAt":"2026-08-01T08:12:30Z","createdBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"modifiedAt":"2026-08-20T08:30:00Z","modifiedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"secretRotatedAt":"2026-08-10T09:00:00Z","secretRotatedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"expiresAt":"2026-12-31T22:59:00Z","revokedAt":null,"revokedBy":null,"lastUsedAt":null}"""
  private val OAuthRotatedClientExampleJson =
    """{"clientId":"epoca_oauth_client_v1_rotated","name":"Reporting warehouse","status":"Active","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects"},"version":5,"policyVersion":3,"secretVersion":3,"serviceAccount":{"userId":"22222222-2222-4222-8222-222222222222","displayName":"Integration Reporting warehouse"},"createdAt":"2026-08-01T08:12:30Z","createdBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"modifiedAt":"2026-08-20T09:00:00Z","modifiedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"secretRotatedAt":"2026-08-20T09:00:00Z","secretRotatedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"expiresAt":"2026-12-31T22:59:00Z","revokedAt":null,"revokedBy":null,"lastUsedAt":null}"""
  private val OAuthRevokedClientExampleJson =
    """{"clientId":"epoca_oauth_client_v1_revoked","name":"Reporting warehouse","status":"Revoked","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects"},"version":5,"policyVersion":3,"secretVersion":2,"serviceAccount":{"userId":"22222222-2222-4222-8222-222222222222","displayName":"Integration Reporting warehouse"},"createdAt":"2026-08-01T08:12:30Z","createdBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"modifiedAt":"2026-08-20T09:30:00Z","modifiedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"secretRotatedAt":"2026-08-10T09:00:00Z","secretRotatedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"expiresAt":"2026-12-31T22:59:00Z","revokedAt":"2026-08-20T09:30:00Z","revokedBy":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"lastUsedAt":null}"""
  private val OAuthClientPageEmptyJson     = """{"count":0,"results":[]}"""
  private val OAuthClientPageExampleJson   = s"""{"count":1,"results":[$OAuthExpiredClientExampleJson]}"""
  private val OAuthCreateSecretExampleJson = s"""{"client":$OAuthCreatedClientExampleJson,"clientSecret":"returned-once-after-create"}"""
  private val OAuthRotateSecretExampleJson = s"""{"client":$OAuthRotatedClientExampleJson,"clientSecret":"returned-once-after-rotation"}"""
  private val OAuthAuditPageEmptyJson      = """{"count":0,"results":[]}"""
  private val OAuthAuditPageExampleJson =
    """{"count":1,"results":[{"id":"44444444-4444-4444-8444-444444444444","occurredAt":"2026-08-19T11:42:10Z","actorType":"Human","actorUserId":"33333333-3333-4333-8333-333333333333","actor":{"userId":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"eventType":"Update","outcome":"Success","metadata":{"beforeExpiresAt":null,"afterExpiresAt":"2026-12-31T22:59:00Z"}}]}"""
  private val GeneratedPivotExportSchemaPrefix = "pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService."

  private val SourceProjectClientJson =
    s"""{"projectId":"${OverrideProjectIds(1)}","projectName":"Original Project","clientId":"${OverrideClientIds(
        1
      )}","clientName":"Original Client","clientColor":"#336699","clientTexture":"","projectDescription":null}"""
  private val EffectiveProjectClientJson =
    s"""{"projectId":"${OverrideProjectIds.head}","projectName":"Corrected Project","clientId":"${OverrideClientIds.head}","clientName":"Corrected Client","clientColor":"#996633","clientTexture":"","projectDescription":null}"""
  private val SourceAuditProjectClientJson =
    s"""{"projectId":"${OverrideProjectIds(1)}","projectName":"Original Project","clientId":"${OverrideClientIds(
        1
      )}","clientName":"Original Client"}"""
  private val EffectiveAuditProjectClientJson =
    s"""{"projectId":"${OverrideProjectIds.head}","projectName":"Corrected Project","clientId":"${OverrideClientIds.head}","clientName":"Corrected Client"}"""
  private val SourceSnapshotJson    = s"""{"projectClient":$SourceAuditProjectClientJson,"taskId":"ORIGINAL-1"}"""
  private val EffectiveSnapshotJson = s"""{"projectClient":$EffectiveAuditProjectClientJson,"taskId":"CT-567"}"""

  private def overrideExample(
    active: Boolean,
    version: Long,
    projectIdJson: String,
    taskJson: String,
    undoJson: Option[String] = None
  ): String = {
    val actor     = if (version == 0) "null" else s"""{"id":"$ActorId","displayName":"Contract Actor"}"""
    val changedAt = if (version == 0) "null" else s""""$ChangedAt""""
    val undo      = undoJson.fold("")(value => s""", "undo":$value""")
    s"""{"active":$active,"hasHistory":${version > 0},"version":$version,"projectId":$projectIdJson,"taskId":$taskJson,"lastChangedBy":$actor,"lastChangedAt":$changedAt,"permissions":{"canEdit":true,"canDelete":true,"canViewHistory":true,"allowedProjectIds":["${OverrideProjectIds.head}","${OverrideProjectIds(
        1
      )}"]}$undo}"""
  }

  private def workUnitExample(
    metadata: String,
    revision: Long,
    effectiveProject: String,
    effectiveTask: String
  ): String =
    s"""{"id":"44444444-4444-4444-8444-444444444444","userId":"$ActorId","userFullName":"Contract Actor","date":"2026-08-06","duration":"PT2H","entryType":"Worklog","description":"Auditable worklog","taskId":"ORIGINAL-1","state":"Active","createdAt":"$ChangedAt","modifiedAt":"$ChangedAt","projectClient":$SourceProjectClientJson,"effectiveProjectClient":$effectiveProject,"effectiveTaskId":$effectiveTask,"revision":$revision,"override":$metadata}"""

  private val InheritTaskJson   = """{"mode":"Inherit"}"""
  private val SetTaskJson       = """{"mode":"Set","value":"CT-567"}"""
  private val TargetProjectJson = s""""${OverrideProjectIds.head}""""
  private val EmptyOverrideJson =
    workUnitExample(overrideExample(false, 0, "null", InheritTaskJson), 0, SourceProjectClientJson, """"ORIGINAL-1"""")
  private val ProjectOnlyOverrideJson =
    workUnitExample(overrideExample(true, 5, TargetProjectJson, InheritTaskJson), 5, EffectiveProjectClientJson, """"ORIGINAL-1"""")
  private val CombinedOverrideJson =
    workUnitExample(overrideExample(true, 5, TargetProjectJson, SetTaskJson), 5, EffectiveProjectClientJson, """"CT-567"""")
  private val DeletedOverrideJson =
    workUnitExample(overrideExample(false, 6, "null", InheritTaskJson), 6, SourceProjectClientJson, """"ORIGINAL-1"""")
  private val ProjectOnlyRequestJson =
    s"""{"expectedRevision":4,"projectId":$TargetProjectJson,"taskId":$InheritTaskJson}"""
  private val CombinedRequestJson =
    s"""{"expectedRevision":4,"projectId":$TargetProjectJson,"taskId":$SetTaskJson}"""
  private val UndoRequestJson =
    """{"expectedRevision":5,"targetEventId":"55555555-5555-4555-8555-555555555555"}"""
  private val UndoAvailabilityJson =
    """{"candidateEventId":null,"canUndo":false,"reason":"LegacyBoundary"}"""
  private val UndoResponseJson =
    workUnitExample(overrideExample(false, 6, "null", InheritTaskJson, Some(UndoAvailabilityJson)), 6, SourceProjectClientJson, """"ORIGINAL-1"""")
  private val EmptyHistoryJson =
    """{"count":0,"pageNumber":1,"pageSize":25,"upToVersion":0,"results":[]}"""
  private val HistoryJson =
    s"""{"count":1,"pageNumber":1,"pageSize":25,"upToVersion":5,"results":[{"id":"55555555-5555-4555-8555-555555555555","version":5,"operation":"Replace","actor":{"id":"$ActorId","displayName":"Contract Actor"},"changedAt":"$ChangedAt","provenance":"WorkUnitOverride","before":{"source":$SourceSnapshotJson,"effective":$SourceSnapshotJson,"projectId":null,"taskIdOverrideSet":false,"taskId":$InheritTaskJson},"after":{"source":$SourceSnapshotJson,"effective":$EffectiveSnapshotJson,"projectId":$TargetProjectJson,"taskIdOverrideSet":true,"taskId":$SetTaskJson},"legacyBefore":null,"legacyAfter":null}]}"""
  private val BuildInfoJson = s"""{"commitSha":"$CommitSha"}"""
  private val MinimalPivotRequestJson =
    """{"rows":[],"columns":[],"values":["DurationSum"],"filters":[],"options":{"rowSubtotals":false,"columnSubtotals":false,"grandTotals":false}}"""
  private val MaximalPivotRequestJson =
    """{"rows":["UserKey","ProjectKey"],"columns":["YearKey","MonthYearKey"],"values":["DurationSum","DurationAvg","EntriesCount"],"filters":[{"key":"ClientKey","includedValues":[null,"55555555-5555-4555-8555-555555555555"]},{"key":"DateKey","includedValues":["2026-08-01"]},{"key":"EntryTypeKey","includedValues":["Worklog","Note"]}],"options":{"rowSubtotals":true,"columnSubtotals":true,"grandTotals":true}}"""
  private val MinimalPivotResponseJson =
    """{"cells":[{"row":{"kind":"Leaf","path":[]},"column":{"kind":"Leaf","path":[]},"values":{"durationSum":"PT0S"}}],"filterMembers":[],"result":{"cellCount":1,"displayValueCellCount":1,"maxDisplayValueCellCount":9600}}"""
  private val InvalidConfigurationJson =
    """{"code":"PIVOT_INVALID_CONFIGURATION","message":"Invalid pivot configuration","violations":[{"path":"values","code":"EMPTY_VALUES"}]}"""
  private val ResultTooLargeJson =
    """{"code":"PIVOT_RESULT_TOO_LARGE","message":"Pivot result exceeds the configured limit","estimatedDisplayValueCellCount":24000,"maxDisplayValueCellCount":9600}"""
  private val ForbiddenProblemJson =
    """{"type":"result:work-unit-pivot-query-service:result","status":403,"title":"User's role doesn't allow to access pivot table","instance":"cid:ct-535-pivot-query-route"}"""
  private val ExportForbiddenProblemJson =
    """{"type":"result:work-unit-pivot-query-service:result","status":403,"title":"User's role doesn't allow to access pivot table","instance":"cid:ct-534-pivot-export-route"}"""
  private val MinimalPivotExportRequestJson =
    s"""{"query":$MinimalPivotRequestJson,"projection":{"effectiveDateTo":"2026-08-31"},"presentation":{"fieldLabels":{"UserKey":"User","ProjectKey":"Project","ClientKey":"Client","YearKey":"Year","MonthYearKey":"Month","DateKey":"Date","EntryTypeKey":"Entry type","TaskKey":"Task"},"measureLabels":{"DurationSum":"Total duration","DurationAvg":"Average duration","EntriesCount":"Entries"},"subtotalLabel":"Subtotal","grandTotalLabel":"Grand total","noClientLabel":"No client","noTaskLabel":"No task","memberLabels":[]}}"""
  private val MaximalPivotExportRequestJson =
    s"""{"query":$MaximalPivotRequestJson,"projection":{"effectiveDateTo":"2026-08-31"},"presentation":{"fieldLabels":{"UserKey":"User","ProjectKey":"Project","ClientKey":"Client","YearKey":"Year","MonthYearKey":"Month","DateKey":"Date","EntryTypeKey":"Entry type","TaskKey":"Task"},"measureLabels":{"DurationSum":"Total duration","DurationAvg":"Average duration","EntriesCount":"Entries"},"subtotalLabel":"Subtotal","grandTotalLabel":"Grand total","noClientLabel":"No client","noTaskLabel":"No task","memberLabels":[{"key":"UserKey","value":"33333333-3333-4333-8333-333333333333","label":"Alice"},{"key":"ProjectKey","value":"11111111-1111-4111-8111-111111111111","label":"Project One"},{"key":"ClientKey","value":null,"label":"No client"},{"key":"YearKey","value":2026,"label":"2026"},{"key":"MonthYearKey","value":"2026-08","label":"August 2026"},{"key":"DateKey","value":"2026-08-01","label":"1 August 2026"},{"key":"EntryTypeKey","value":"Worklog","label":"Worklog"},{"key":"TaskKey","value":"PROJ-1","label":"Task one"}]}}"""
  private val InvalidExportRequestJson =
    """{"code":"PIVOT_INVALID_EXPORT_REQUEST","message":"Invalid pivot export request","violations":[{"path":"presentation.subtotalLabel","code":"INVALID_PRESENTATION_VALUE"}]}"""
  private val UnauthorizedProblemJson =
    """{"type":"authentication-failed","status":401,"title":"Could not authorize","detail":"The resource requires authentication, which was not supplied with the request","instance":"cid:ct-534-pivot-export-route"}"""
  private val PayloadTooLargeProblemJson =
    """{"type":"malformed-request-content","status":413,"title":"The request content was malformed","instance":"cid:ct-534-pivot-export-route"}"""
  private val ExportOverloadedProblemJson =
    """{"type":"result:pivot-export-overloaded","status":503,"title":"Pivot export capacity is exhausted","instance":"cid:ct-534-pivot-export-route"}"""
  private val NotFoundText    = "The requested resource could not be found but may be available again in the future."
  private val PivotKeys       = Seq("UserKey", "ProjectKey", "ClientKey", "YearKey", "MonthYearKey", "DateKey", "EntryTypeKey", "TaskKey")
  private val PivotValues     = Seq("DurationSum", "DurationAvg", "EntriesCount")
  private val CoordinateKinds = Seq("Leaf", "Subtotal", "GrandTotal")
  private val ViolationCodes = Seq(
    "MISSING_REQUIRED_PROPERTY",
    "INVALID_PROPERTY_TYPE",
    "UNSUPPORTED_FIELD",
    "UNSUPPORTED_MEASURE",
    "DUPLICATE_FIELD",
    "FIELD_ASSIGNED_TO_MULTIPLE_AREAS",
    "EMPTY_VALUES",
    "DUPLICATE_MEASURE",
    "INVALID_FILTER_VALUE",
    "EMPTY_SELECTION",
    "DUPLICATE_FILTER_VALUE"
  )
  private val PivotComponentSchemasTemplate =
    """{
      "WorkUnitPivotKey": {
        "type": "string",
        "enum": ["UserKey", "ProjectKey", "ClientKey", "YearKey", "MonthYearKey", "DateKey", "EntryTypeKey", "TaskKey"]
      },
      "WorkUnitPivotValue": {
        "type": "string",
        "enum": ["DurationSum", "DurationAvg", "EntriesCount"]
      },
      "WorkUnitPivotCoordinateKind": {
        "type": "string",
        "enum": ["Leaf", "Subtotal", "GrandTotal"]
      },
      "WorkUnitPivotUuid": {
        "type": "string",
        "format": "uuid",
        "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
      },
      "WorkUnitPivotYear": {
        "type": "integer",
        "format": "int32",
        "minimum": -2147483648,
        "maximum": 2147483647
      },
      "WorkUnitPivotMonth": {
        "type": "string",
        "pattern": "^[0-9]{4}-(0[1-9]|1[0-2])$"
      },
      "WorkUnitPivotDate": {
        "type": "string",
        "format": "date",
        "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
      },
      "WorkUnitPivotEntryType": {
        "type": "string",
        "enum": ["Worklog", "Note"]
      },
      "WorkUnitPivotNullableUuid": {
        "type": "string",
        "format": "uuid",
        "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        "nullable": true
      },
      "WorkUnitPivotTaskId": {
        "type": "string",
        "nullable": true
      },
      "WorkUnitPivotFieldValue": {
        "oneOf": [
          { "REF": "#/components/schemas/WorkUnitPivotUuid" },
          { "REF": "#/components/schemas/WorkUnitPivotYear" },
          { "REF": "#/components/schemas/WorkUnitPivotMonth" },
          { "REF": "#/components/schemas/WorkUnitPivotDate" },
          { "REF": "#/components/schemas/WorkUnitPivotEntryType" },
          { "REF": "#/components/schemas/WorkUnitPivotTaskId" },
          { "type": "string", "nullable": true, "enum": [null] }
        ]
      },
      "WorkUnitPivotOptions": {
        "type": "object",
        "additionalProperties": false,
        "required": ["rowSubtotals", "columnSubtotals", "grandTotals"],
        "properties": {
          "rowSubtotals": { "type": "boolean" },
          "columnSubtotals": { "type": "boolean" },
          "grandTotals": { "type": "boolean" }
        }
      },
      "WorkUnitPivotUserFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["UserKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotUuid" } }
        }
      },
      "WorkUnitPivotProjectFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["ProjectKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotUuid" } }
        }
      },
      "WorkUnitPivotClientFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["ClientKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotNullableUuid" } }
        }
      },
      "WorkUnitPivotYearFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["YearKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotYear" } }
        }
      },
      "WorkUnitPivotMonthFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["MonthYearKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotMonth" } }
        }
      },
      "WorkUnitPivotDateFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["DateKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotDate" } }
        }
      },
      "WorkUnitPivotEntryTypeFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["EntryTypeKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotEntryType" } }
        }
      },
      "WorkUnitPivotTaskFilter": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "includedValues"],
        "properties": {
          "key": { "type": "string", "enum": ["TaskKey"] },
          "includedValues": { "type": "array", "nullable": true, "minItems": 1, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotTaskId" } }
        }
      },
      "WorkUnitPivotFilter": {
        "oneOf": [
          { "REF": "#/components/schemas/WorkUnitPivotUserFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotProjectFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotClientFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotYearFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotMonthFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotDateFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotEntryTypeFilter" },
          { "REF": "#/components/schemas/WorkUnitPivotTaskFilter" }
        ]
      },
      "WorkUnitPivotQueryRequest": {
        "type": "object",
        "additionalProperties": false,
        "description": "A dimension may occur only once across rows, columns, and filters. Draft 2020-12 JSON Schema and runtime validation are authoritative for this cross-area rule because OpenAPI 3.0 has no contains keyword.",
        "required": ["rows", "columns", "values", "filters", "options"],
        "properties": {
          "rows": { "type": "array", "maxItems": 8, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotKey" } },
          "columns": { "type": "array", "maxItems": 8, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotKey" } },
          "values": { "type": "array", "minItems": 1, "maxItems": 3, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotValue" } },
          "filters": { "type": "array", "maxItems": 8, "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotFilter" } },
          "options": { "REF": "#/components/schemas/WorkUnitPivotOptions" }
        }
      },
      "WorkUnitPivotPathSegment": {
        "oneOf": [
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["UserKey", "ProjectKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotUuid" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["ClientKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotNullableUuid" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["YearKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotYear" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["MonthYearKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotMonth" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["DateKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotDate" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["EntryTypeKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotEntryType" } } },
          { "type": "object", "additionalProperties": false, "required": ["key", "value"], "properties": { "key": { "type": "string", "enum": ["TaskKey"] }, "value": { "REF": "#/components/schemas/WorkUnitPivotTaskId" } } }
        ]
      },
      "WorkUnitPivotCoordinate": {
        "type": "object",
        "additionalProperties": false,
        "required": ["kind", "path"],
        "properties": {
          "kind": { "REF": "#/components/schemas/WorkUnitPivotCoordinateKind" },
          "path": { "type": "array", "maxItems": 8, "items": { "REF": "#/components/schemas/WorkUnitPivotPathSegment" } }
        }
      },
      "WorkUnitPivotMetricValues": {
        "type": "object",
        "additionalProperties": false,
        "minProperties": 1,
        "properties": {
          "durationSum": { "type": "string", "pattern": "^PT(?=[0-9])(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+(?:\\.[0-9]{1,9})?S)?$" },
          "durationAvg": { "type": "string", "pattern": "^PT(?=[0-9])(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+(?:\\.[0-9]{1,9})?S)?$", "nullable": true },
          "entriesCount": { "type": "integer", "format": "int64", "minimum": 0, "maximum": 9223372036854775807 }
        }
      },
      "WorkUnitPivotCell": {
        "type": "object",
        "additionalProperties": false,
        "required": ["row", "column", "values"],
        "properties": {
          "row": { "REF": "#/components/schemas/WorkUnitPivotCoordinate" },
          "column": { "REF": "#/components/schemas/WorkUnitPivotCoordinate" },
          "values": { "REF": "#/components/schemas/WorkUnitPivotMetricValues" }
        }
      },
      "WorkUnitPivotUserFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["UserKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotUuid" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotProjectFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["ProjectKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotUuid" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotClientFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["ClientKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotNullableUuid" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotYearFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["YearKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotYear" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotMonthFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["MonthYearKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotMonth" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotDateFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["DateKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotDate" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotEntryTypeFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["EntryTypeKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotEntryType" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotTaskFilterMembers": {
        "type": "object", "additionalProperties": false, "required": ["key", "members"],
        "properties": {
          "key": { "type": "string", "enum": ["TaskKey"] },
          "members": { "type": "array", "uniqueItems": true, "items": { "type": "object", "additionalProperties": false, "required": ["value", "available"], "properties": { "value": { "REF": "#/components/schemas/WorkUnitPivotTaskId" }, "available": { "type": "boolean" } } } }
        }
      },
      "WorkUnitPivotFilterMembers": {
        "oneOf": [
          { "REF": "#/components/schemas/WorkUnitPivotUserFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotProjectFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotClientFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotYearFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotMonthFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotDateFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotEntryTypeFilterMembers" },
          { "REF": "#/components/schemas/WorkUnitPivotTaskFilterMembers" }
        ]
      },
      "WorkUnitPivotResultMetadata": {
        "type": "object",
        "additionalProperties": false,
        "required": ["cellCount", "displayValueCellCount", "maxDisplayValueCellCount"],
        "properties": {
          "cellCount": { "type": "integer", "format": "int64", "minimum": 0, "maximum": 9223372036854775807 },
          "displayValueCellCount": { "type": "integer", "format": "int64", "minimum": 0, "maximum": 9223372036854775807 },
          "maxDisplayValueCellCount": { "type": "integer", "format": "int64", "minimum": 1, "maximum": 9223372036854775807 }
        }
      },
      "WorkUnitPivotQueryResponse": {
        "type": "object",
        "additionalProperties": false,
        "required": ["cells", "filterMembers", "result"],
        "properties": {
          "cells": { "type": "array", "items": { "REF": "#/components/schemas/WorkUnitPivotCell" } },
          "filterMembers": { "type": "array", "uniqueItems": true, "items": { "REF": "#/components/schemas/WorkUnitPivotFilterMembers" } },
          "result": { "REF": "#/components/schemas/WorkUnitPivotResultMetadata" }
        }
      },
      "WorkUnitPivotInvalidConfigurationError": {
        "type": "object",
        "additionalProperties": false,
        "required": ["code", "message", "violations"],
        "properties": {
          "code": { "type": "string", "enum": ["PIVOT_INVALID_CONFIGURATION"] },
          "message": { "type": "string", "enum": ["Invalid pivot configuration"] },
          "violations": {
            "type": "array",
            "minItems": 1,
            "items": {
              "type": "object",
              "additionalProperties": false,
              "required": ["path", "code"],
              "properties": {
                "path": { "type": "string", "minLength": 1 },
                "code": { "type": "string", "enum": ["MISSING_REQUIRED_PROPERTY", "INVALID_PROPERTY_TYPE", "UNSUPPORTED_FIELD", "UNSUPPORTED_MEASURE", "DUPLICATE_FIELD", "FIELD_ASSIGNED_TO_MULTIPLE_AREAS", "EMPTY_VALUES", "DUPLICATE_MEASURE", "INVALID_FILTER_VALUE", "EMPTY_SELECTION", "DUPLICATE_FILTER_VALUE"] }
              }
            }
          }
        }
      },
      "WorkUnitPivotResultTooLargeError": {
        "type": "object",
        "additionalProperties": false,
        "required": ["code", "message", "estimatedDisplayValueCellCount", "maxDisplayValueCellCount"],
        "properties": {
          "code": { "type": "string", "enum": ["PIVOT_RESULT_TOO_LARGE"] },
          "message": { "type": "string", "enum": ["Pivot result exceeds the configured limit"] },
          "estimatedDisplayValueCellCount": { "type": "integer", "format": "int64", "minimum": 0, "maximum": 9223372036854775807 },
          "maxDisplayValueCellCount": { "type": "integer", "format": "int64", "minimum": 1, "maximum": 9223372036854775807 }
        }
      },
      "WorkUnitPivotProblemDetails": {
        "type": "object",
        "additionalProperties": false,
        "required": ["type", "status", "title", "instance"],
        "properties": {
          "type": { "type": "string", "description": "Stable relative or absolute problem type identifier emitted by the platform." },
          "status": { "type": "integer", "format": "int32", "minimum": 400, "maximum": 599 },
          "title": { "type": "string" },
          "detail": { "type": "string" },
          "instance": { "type": "string", "format": "uri" },
          "extension": { "type": "object", "additionalProperties": true }
        }
      }
    }"""
  private val PivotComponentSchemasJson = PivotComponentSchemasTemplate.replace("\"REF\"", "\"" + "$" + "ref\"")
  private val PivotExportComponentSchemasTemplate =
    """{
      "WorkUnitPivotExportLabel": {
        "type": "string",
        "minLength": 1,
        "maxLength": 140,
        "description": "Normalized presentation text. At most 140 Unicode code points and 560 UTF-8 bytes; remaining C0/C1 controls are rejected by runtime validation."
      },
      "WorkUnitPivotExportFieldLabels": {
        "type": "object",
        "additionalProperties": false,
        "required": ["UserKey", "ProjectKey", "ClientKey", "YearKey", "MonthYearKey", "DateKey", "EntryTypeKey", "TaskKey"],
        "properties": {
          "UserKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "ProjectKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "ClientKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "YearKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "MonthYearKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "DateKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "EntryTypeKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "TaskKey": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" }
        }
      },
      "WorkUnitPivotExportMeasureLabels": {
        "type": "object",
        "additionalProperties": false,
        "required": ["DurationSum", "DurationAvg", "EntriesCount"],
        "properties": {
          "DurationSum": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "DurationAvg": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "EntriesCount": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" }
        }
      },
      "WorkUnitPivotExportMemberLabel": {
        "type": "object",
        "additionalProperties": false,
        "required": ["key", "value", "label"],
        "description": "The runtime validator requires value to match its key-specific raw FieldValue type and rejects duplicate key/value identities.",
        "properties": {
          "key": { "REF": "#/components/schemas/WorkUnitPivotKey" },
          "value": { "REF": "#/components/schemas/WorkUnitPivotFieldValue" },
          "label": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" }
        }
      },
      "WorkUnitPivotExportPresentation": {
        "type": "object",
        "additionalProperties": false,
        "required": ["fieldLabels", "measureLabels", "subtotalLabel", "grandTotalLabel", "noClientLabel", "noTaskLabel", "memberLabels"],
        "properties": {
          "fieldLabels": { "REF": "#/components/schemas/WorkUnitPivotExportFieldLabels" },
          "measureLabels": { "REF": "#/components/schemas/WorkUnitPivotExportMeasureLabels" },
          "subtotalLabel": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "grandTotalLabel": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "noClientLabel": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "noTaskLabel": { "REF": "#/components/schemas/WorkUnitPivotExportLabel" },
          "memberLabels": {
            "type": "array",
            "maxItems": 72000,
            "description": "Bounded by maxDisplayValueCellCount x 8 (72,000 identities for the current 9,000-cell limit). Each canonical entry is at most 768 bytes; the canonical request budget is 63,296,000 bytes inside the 67,108,864-byte transport limit.",
            "items": { "REF": "#/components/schemas/WorkUnitPivotExportMemberLabel" }
          }
        }
      },
      "WorkUnitPivotExportProjection": {
        "type": "object",
        "additionalProperties": false,
        "required": ["effectiveDateTo"],
        "properties": {
          "effectiveDateTo": { "type": "string", "format": "date" }
        }
      },
      "WorkUnitPivotExportRequest": {
        "type": "object",
        "additionalProperties": false,
        "required": ["query", "presentation"],
        "properties": {
          "query": { "REF": "#/components/schemas/WorkUnitPivotQueryRequest" },
          "projection": { "REF": "#/components/schemas/WorkUnitPivotExportProjection" },
          "presentation": { "REF": "#/components/schemas/WorkUnitPivotExportPresentation" }
        }
      },
      "WorkUnitPivotExportViolationCode": {
        "type": "string",
        "enum": ["MISSING_REQUIRED_PROPERTY", "INVALID_PROPERTY_TYPE", "UNSUPPORTED_FIELD", "INVALID_PROJECTION_VALUE", "INVALID_PRESENTATION_VALUE", "DUPLICATE_PRESENTATION_MEMBER", "PRESENTATION_LIMIT_EXCEEDED"]
      },
      "WorkUnitPivotExportViolation": {
        "type": "object",
        "additionalProperties": false,
        "required": ["path", "code"],
        "properties": {
          "path": { "type": "string", "minLength": 1 },
          "code": { "REF": "#/components/schemas/WorkUnitPivotExportViolationCode" }
        }
      },
      "WorkUnitPivotInvalidExportRequest": {
        "type": "object",
        "additionalProperties": false,
        "required": ["code", "message", "violations"],
        "properties": {
          "code": { "type": "string", "enum": ["PIVOT_INVALID_EXPORT_REQUEST"] },
          "message": { "type": "string", "enum": ["Invalid pivot export request"] },
          "violations": {
            "type": "array",
            "minItems": 1,
            "items": { "REF": "#/components/schemas/WorkUnitPivotExportViolation" }
          }
        }
      }
    }"""
  private val PivotExportComponentSchemasJson = PivotExportComponentSchemasTemplate.replace("\"REF\"", "\"" + "$" + "ref\"")

  def main(args: Array[String]): Unit = {
    if (args.length != 1) throw new IllegalArgumentException("Expected the generated OpenAPI YAML path")

    val outputPath = Path.of(args.head)
    val input      = InvitationOpenApiContracts.publish(Files.readString(outputPath, StandardCharsets.UTF_8))
    verifyOAuthProtectedOperationRegistry(input)
    val processed = publishOAuthOperationContracts(process(input))
    val temporary = Files.createTempFile(outputPath.getParent, "openapi-contract-", ".yml")
    Files.writeString(temporary, processed, StandardCharsets.UTF_8)
    Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    ()
  }

  def verifyOAuthProtectedOperationRegistry(input: String): Unit = {
    val root  = requiredObject(Yaml.mapper().readTree(input), "OpenAPI document")
    val paths = requiredObject(root.path("paths"), "OpenAPI paths")
    val inventory = paths
      .properties()
      .asScala
      .flatMap { pathEntry =>
        requiredObject(pathEntry.getValue, s"OpenAPI path ${pathEntry.getKey}").properties().asScala.flatMap { operationEntry =>
          val methodName = operationEntry.getKey.toUpperCase
          HttpMethods.getForKey(methodName).flatMap { method =>
            val operation = requiredObject(operationEntry.getValue, s"$methodName ${pathEntry.getKey} operation")
            val protectedByBearer = operation
              .path("security")
              .elements()
              .asScala
              .exists(requirement => Seq("bearerAuth", "humanBearer").exists(scheme => requirement.path(scheme).isArray))
            Option.when(protectedByBearer)(OAuthHttpOperation(method, pathEntry.getKey))
          }
        }
      }
      .toSet

    OAuthOperationRegistry.default.validateProtectedInventory(inventory).left.foreach { drift =>
      val formatted = drift.toVector.sortBy(operation => operation.method.value -> operation.pathTemplate).map { operation =>
        s"${operation.method.value} ${operation.pathTemplate}"
      }
      throw new IllegalStateException(s"OAuth protected-operation registry drift: ${formatted.mkString(", ")}")
    }
  }

  def publishOAuthOperationContracts(input: String): String = {
    val root = requiredObject(Yaml.mapper().readTree(input), "OpenAPI document")
    OAuthOperationRegistry.default.oauthOperations.foreach { registered =>
      val pointer   = s"/paths/${escape(registered.http.pathTemplate)}/${registered.http.method.value.toLowerCase}"
      val operation = requiredObject(root.at(pointer), s"${registered.http.method.value} ${registered.http.pathTemplate} operation")
      val currentId = operation.path("operationId")
      if (!currentId.isMissingNode && currentId.asText() != registered.id)
        throw new IllegalStateException(s"Conflicting OAuth operationId at ${registered.http.method.value} ${registered.http.pathTemplate}")
      operation.put("operationId", registered.id)
      operation.put("x-epoca-oauth-scope", registered.requiredScope.value)
    }
    Yaml.pretty().writeValueAsString(root)
  }

  def process(input: String): String = {
    val root = requiredObject(Yaml.mapper().readTree(input), "OpenAPI document")

    replaceRepeatableParameter(root, ReportPath, "get", "projectId", OverrideProjectIds)
    replaceRepeatableParameter(root, ReportPath, "get", "clientId", OverrideClientIds)
    replaceRepeatableParameter(root, PivotPath, "post", "projectId", PivotProjectIds)
    replaceRepeatableParameter(root, PivotPath, "post", "userId", PivotUserIds)
    replaceRepeatableParameter(root, PivotPath, "post", "clientId", PivotClientIds)
    requireResponseStatuses(root, PivotPath, "post", PivotResponseStatuses)
    normalizePivotContract(root)
    replaceRepeatableParameter(root, PivotExportPath, "post", "projectId", PivotProjectIds)
    replaceRepeatableParameter(root, PivotExportPath, "post", "userId", PivotUserIds)
    replaceRepeatableParameter(root, PivotExportPath, "post", "clientId", PivotClientIds)
    requireResponseStatuses(root, PivotExportPath, "post", PivotExportResponseStatuses)
    normalizePivotExportContract(root)
    requireText(root, s"/paths/${escape(BuildInfoPath)}/get/responses/200/content/application~1json/schema/$$ref", BuildInfoSchemaRef)
    normalizeBuildInfoSchema(root)
    normalizeOAuthManagementContractIfPresent(root)
    normalizeWorkUnitOverrideContract(root)

    replaceExamples(root, s"/paths/${escape(BuildInfoPath)}/get/responses/200/content/application~1json", BuildInfoJson, BuildInfoJson)
    replaceExamples(root, s"/paths/${escape(CurrentPath)}/get/responses/200/content/application~1json", EmptyOverrideJson, CombinedOverrideJson)
    replaceExamples(root, s"/paths/${escape(CurrentPath)}/put/requestBody/content/application~1json", ProjectOnlyRequestJson, CombinedRequestJson)
    replaceExamples(root, s"/paths/${escape(CurrentPath)}/put/responses/200/content/application~1json", ProjectOnlyOverrideJson, CombinedOverrideJson)
    replaceExamples(root, s"/paths/${escape(CurrentPath)}/delete/responses/200/content/application~1json", DeletedOverrideJson, DeletedOverrideJson)
    setExamples(root, s"/paths/${escape(UndoPath)}/post/requestBody/content/application~1json", UndoRequestJson, UndoRequestJson)
    setExamples(root, s"/paths/${escape(UndoPath)}/post/responses/200/content/application~1json", UndoResponseJson, UndoResponseJson)
    replaceExamples(root, s"/paths/${escape(HistoryPath)}/get/responses/200/content/application~1json", EmptyHistoryJson, HistoryJson)

    verifyNoDanglingLocalRefs(root)

    Yaml.pretty().writeValueAsString(root)
  }

  val WorkUnitOverrideComponentSchemasJson: String = {
    val uuid         = """{"type":"string","format":"uuid"}"""
    val nullableUuid = """{"type":"string","format":"uuid","nullable":true}"""
    val text         = """{"type":"string"}"""
    val nullableText = """{"type":"string","nullable":true}"""
    val integer      = """{"type":"integer","format":"int64","minimum":0,"maximum":9007199254740991}"""
    val timestamp    = """{"type":"string","format":"date-time"}"""
    val nullOnly     = """{"type":"object","nullable":true,"enum":[null]}"""
    val actor = s"""{"type":"object","additionalProperties":false,"required":["id","displayName"],"properties":{"id":$uuid,"displayName":$text}}"""
    val task =
      """{"oneOf":[{"type":"object","additionalProperties":false,"required":["mode"],"properties":{"mode":{"type":"string","enum":["Inherit","Clear"]}}},{"type":"object","additionalProperties":false,"required":["mode","value"],"properties":{"mode":{"type":"string","enum":["Set"]},"value":{"type":"string","minLength":1,"maxLength":255}}}]}"""
    val project =
      s"""{"type":"object","nullable":true,"additionalProperties":false,"required":["projectId","projectName","clientId","clientName"],"properties":{"projectId":$uuid,"projectName":$nullableText,"clientId":$nullableUuid,"clientName":$nullableText}}"""
    val values =
      s"""{"type":"object","additionalProperties":false,"required":["projectClient","taskId"],"properties":{"projectClient":$project,"taskId":$nullableText}}"""
    val snapshot =
      s"""{"type":"object","additionalProperties":false,"required":["source","effective","projectId","taskIdOverrideSet","taskId"],"properties":{"source":$values,"effective":$values,"projectId":$nullableUuid,"taskIdOverrideSet":{"type":"boolean"},"taskId":$task}}"""
    val nullableSnapshot = snapshot.replaceFirst("\"type\":\"object\"", "\"type\":\"object\",\"nullable\":true")
    val nullableValues   = values.replaceFirst("\"type\":\"object\"", "\"type\":\"object\",\"nullable\":true")
    val nullableActor    = actor.replaceFirst("\"type\":\"object\"", "\"type\":\"object\",\"nullable\":true")
    val undo =
      s"""{"type":"object","nullable":true,"additionalProperties":false,"required":["candidateEventId","canUndo","reason"],"properties":{"candidateEventId":$nullableUuid,"canUndo":{"type":"boolean"},"reason":{"type":"string","nullable":true,"enum":[null,"NoCorrection","LegacyBoundary","Forbidden","TargetUnavailable"]}},"anyOf":[
      {"properties":{"candidateEventId":$uuid,"canUndo":{"enum":[true]},"reason":$nullOnly}},
      {"properties":{"candidateEventId":$nullableUuid,"canUndo":{"enum":[false]},"reason":{"type":"string","enum":["NoCorrection","LegacyBoundary","Forbidden","TargetUnavailable"]}}}
    ]}"""
    val revertedBy =
      s"""{"type":"object","nullable":true,"additionalProperties":false,"required":["eventId","actor","changedAt"],"properties":{"eventId":$uuid,"actor":$actor,"changedAt":$timestamp}}"""
    val permissions =
      s"""{"type":"object","additionalProperties":false,"required":["canEdit","canDelete","canViewHistory","allowedProjectIds"],"properties":{"canEdit":{"type":"boolean"},"canDelete":{"type":"boolean"},"canViewHistory":{"type":"boolean"},"allowedProjectIds":{"type":"array","items":$uuid}}}"""
    val metadata =
      s"""{"type":"object","additionalProperties":false,"required":["active","hasHistory","version","projectId","taskId","lastChangedBy","lastChangedAt","permissions"],"properties":{"active":{"type":"boolean"},"hasHistory":{"type":"boolean"},"version":$integer,"projectId":$nullableUuid,"taskId":$task,"lastChangedBy":$nullableActor,"lastChangedAt":{"type":"string","format":"date-time","nullable":true},"permissions":$permissions,"undo":$undo}}"""
    val dtoProject =
      s"""{"type":"object","nullable":true,"additionalProperties":false,"required":["projectId","projectName","projectDescription","clientId","clientName","clientColor","clientTexture"],"properties":{"projectId":$uuid,"projectName":$text,"projectDescription":$nullableText,"clientId":$uuid,"clientName":$text,"clientColor":$text,"clientTexture":$text}}"""
    val dto =
      s"""{"type":"object","additionalProperties":false,"required":["id","userId","userFullName","date","duration","entryType","description","taskId","state","createdAt","modifiedAt","projectClient","effectiveProjectClient","effectiveTaskId","revision","override"],"properties":{"id":$uuid,"userId":$uuid,"userFullName":$nullableText,"date":{"type":"string","format":"date"},"duration":$text,"entryType":{"type":"string","enum":["Worklog","Note"]},"description":$text,"taskId":$nullableText,"state":{"type":"string","enum":["Active","Archived","Deleted","Draft","GdprAnonymized","Inactive"]},"createdAt":$timestamp,"modifiedAt":$timestamp,"projectClient":$dtoProject,"effectiveProjectClient":$dtoProject,"effectiveTaskId":$nullableText,"revision":$integer,"override":$metadata}}"""
    val history =
      s"""{"type":"object","additionalProperties":false,"required":["id","version","operation","actor","changedAt","provenance","before","after","legacyBefore","legacyAfter"],"properties":{"id":$uuid,"version":$integer,"operation":$text,"actor":$actor,"changedAt":$timestamp,"provenance":{"type":"string","enum":["WorkUnitOverride","LegacyPmSnapshot"]},"before":$nullableSnapshot,"after":$nullableSnapshot,"legacyBefore":$nullableValues,"legacyAfter":$nullableValues,"parentCorrectionId":$nullableUuid,"revertsEventId":$nullableUuid,"revertedBy":$revertedBy},"oneOf":[
      {"properties":{"provenance":{"enum":["WorkUnitOverride"]},"operation":{"enum":["Replace","Clear","Undo"]},"before":$snapshot,"after":$snapshot,"legacyBefore":$nullOnly,"legacyAfter":$nullOnly}},
      {"properties":{"provenance":{"enum":["LegacyPmSnapshot"]},"operation":{"enum":["Replace","Clear"]},"before":$nullOnly,"after":$nullOnly,"legacyBefore":$values,"legacyAfter":$values,"parentCorrectionId":$nullOnly,"revertsEventId":$nullOnly,"revertedBy":$nullOnly}},
      {"properties":{"provenance":{"enum":["WorkUnitOverride"]},"operation":{"not":{"enum":["Replace","Clear","Undo"]}},"before":$snapshot,"after":$snapshot,"legacyBefore":$nullOnly,"legacyAfter":$nullOnly}},
      {"properties":{"provenance":{"enum":["LegacyPmSnapshot"]},"operation":{"not":{"enum":["Replace","Clear","Undo"]}},"before":$nullOnly,"after":$nullOnly,"legacyBefore":$values,"legacyAfter":$values,"parentCorrectionId":$nullOnly,"revertsEventId":$nullOnly,"revertedBy":$nullOnly}}
    ]}"""
    s"""{
      "UndoWorkUnitOverrideRequest":{"type":"object","additionalProperties":false,"required":["expectedRevision","targetEventId"],"properties":{"expectedRevision":$integer,"targetEventId":$uuid}},
      "WorkUnitOverride":$metadata,
      "WorkUnitOverrideResponse":$dto,
      "WorkUnitOverrideHistoryEvent":$history,
      "WorkUnitOverrideHistoryPage":{"type":"object","additionalProperties":false,"required":["count","pageNumber","pageSize","upToVersion","results"],"properties":{"count":$integer,"pageNumber":{"type":"integer","minimum":1},"pageSize":{"type":"integer","minimum":1,"maximum":100},"upToVersion":$integer,"results":{"type":"array","items":{"REF":"#/components/schemas/WorkUnitOverrideHistoryEvent"}}}},
      "WorkUnitOverrideProblem":{"type":"object","additionalProperties":false,"required":["type","status","title","instance"],"properties":{"type":$text,"status":{"type":"integer","enum":[400,401,403,404,409]},"title":$text,"instance":$text,"detail":$text,"extension":{"type":"object"}}}
    }""".replace("\"REF\"", "\"$ref\"")
  }

  private def normalizeWorkUnitOverrideContract(root: ObjectNode): Unit = {
    requireResponseStatuses(root, UndoPath, "post", Seq("200", "400", "401", "403", "404", "409"))
    val schemas    = requiredObject(root.at("/components/schemas"), "OpenAPI schemas")
    val components = requiredObject(Yaml.mapper().readTree(WorkUnitOverrideComponentSchemasJson), "override schemas")
    components.properties().asScala.foreach(entry => schemas.set[JsonNode](entry.getKey, entry.getValue))
    val undo     = requiredObject(root.at(s"/paths/${escape(UndoPath)}/post"), "undo operation")
    val security = undo.path("security")
    if (security != Yaml.mapper().readTree("""[{"humanBearer":[]}]"""))
      throw new IllegalStateException("Undo must use only humanBearer authentication")
    requiredObject(root.path("components"), "components")
      .withObject("/securitySchemes")
      .putObject("humanBearer")
      .put("type", "http")
      .put("scheme", "bearer")
      .put("description", "Human user token only. OAuth clients cannot undo corrections.")
    requiredObject(undo.at("/requestBody/content/application~1json"), "undo request media")
      .set[JsonNode]("schema", refSchema("#/components/schemas/UndoWorkUnitOverrideRequest"))
    requiredObject(undo.path("requestBody"), "undo request").put("required", true)
    Seq(CurrentPath -> "get", CurrentPath -> "put", CurrentPath -> "delete", UndoPath -> "post", HistoryPath -> "get").foreach {
      case (path, method) =>
        val media = requiredObject(root.at(s"/paths/${escape(path)}/$method/responses/200/content/application~1json"), s"$method $path response")
        val name  = if (path == HistoryPath) "WorkUnitOverrideHistoryPage" else "WorkUnitOverrideResponse"
        media.set[JsonNode]("schema", refSchema(s"#/components/schemas/$name"))
    }
    Seq("400", "401", "403", "404", "409").foreach { status =>
      requiredObject(undo.at(s"/responses/$status"), s"undo $status response")
        .withObject("/content")
        .withObject("/application~1json")
        .set[JsonNode]("schema", refSchema("#/components/schemas/WorkUnitOverrideProblem"))
    }
    ()
  }

  private def normalizeOAuthManagementContractIfPresent(root: ObjectNode): Unit = {
    val paths = requiredObject(root.path("paths"), "OpenAPI paths")
    if (paths.has(OAuthCollectionPath)) {
      val schemas = preflightOAuthManagementContract(root)

      val normalized = requiredObject(Yaml.mapper().readTree(OAuthComponentSchemasJson), "normalized OAuth component schemas")
      normalized.properties().asScala.foreach(entry => schemas.set[JsonNode](entry.getKey, entry.getValue))

      normalizeOAuthStatusParameter(requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/get"), s"get $OAuthCollectionPath operation"))
      normalizeOAuthRequest(
        requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/post"), s"post $OAuthCollectionPath operation"),
        OAuthCreateSchemaName
      )
      normalizeOAuthRequest(
        requiredObject(root.at(s"/paths/${escape(OAuthClientPath)}/patch"), s"patch $OAuthClientPath operation"),
        OAuthUpdateSchemaName
      )
      normalizeOAuthRequest(
        requiredObject(root.at(s"/paths/${escape(s"$OAuthClientPath/rotate-secret")}/post"), s"post $OAuthClientPath/rotate-secret operation"),
        OAuthVersionSchemaName
      )
      normalizeOAuthRequest(
        requiredObject(root.at(s"/paths/${escape(s"$OAuthClientPath/revoke")}/post"), s"post $OAuthClientPath/revoke operation"),
        OAuthVersionSchemaName
      )

      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/get"), s"get $OAuthCollectionPath operation"),
        "200",
        OAuthClientPageSchemaName,
        OAuthClientPageEmptyJson,
        OAuthClientPageExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/post"), s"post $OAuthCollectionPath operation"),
        "201",
        OAuthSecretSchemaName,
        OAuthCreateSecretExampleJson,
        OAuthCreateSecretExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthClientPath)}/get"), s"get $OAuthClientPath operation"),
        "200",
        OAuthViewSchemaName,
        OAuthExpiredClientExampleJson,
        OAuthExpiredClientExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthClientPath)}/patch"), s"patch $OAuthClientPath operation"),
        "200",
        OAuthViewSchemaName,
        OAuthUpdatedClientExampleJson,
        OAuthUpdatedClientExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(s"$OAuthClientPath/audit-events")}/get"), s"get $OAuthClientPath/audit-events operation"),
        "200",
        OAuthAuditPageSchemaName,
        OAuthAuditPageEmptyJson,
        OAuthAuditPageExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(s"$OAuthClientPath/revoke")}/post"), s"post $OAuthClientPath/revoke operation"),
        "200",
        OAuthViewSchemaName,
        OAuthRevokedClientExampleJson,
        OAuthRevokedClientExampleJson
      )
      normalizeOAuthSuccessResponse(
        requiredObject(root.at(s"/paths/${escape(s"$OAuthClientPath/rotate-secret")}/post"), s"post $OAuthClientPath/rotate-secret operation"),
        "200",
        OAuthSecretSchemaName,
        OAuthRotateSecretExampleJson,
        OAuthRotateSecretExampleJson
      )

      normalizeOAuthProblemResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/post"), s"post $OAuthCollectionPath operation"),
        "400",
        "OAuth client policy or expiration is invalid.",
        Seq("invalidExpiration" -> oauthProblemJson("result:oauth-client:invalid-expiration", 400, "OAuth client expiration is invalid"))
      )
      normalizeOAuthProblemResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/post"), s"post $OAuthCollectionPath operation"),
        "409",
        "An OAuth client with this name already exists.",
        oauthNameConflictExamples
      )
      normalizeOAuthProblemResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthClientPath)}/patch"), s"patch $OAuthClientPath operation"),
        "400",
        "OAuth client policy or expiration is invalid.",
        Seq("invalidExpiration" -> oauthProblemJson("result:oauth-client:invalid-expiration", 400, "OAuth client expiration is invalid"))
      )
      normalizeOAuthProblemResponse(
        requiredObject(root.at(s"/paths/${escape(OAuthClientPath)}/patch"), s"patch $OAuthClientPath operation"),
        "409",
        "The optimistic version is stale, the client is not active, or its new name already exists.",
        oauthPatchConflictExamples
      )
      Seq(s"$OAuthClientPath/rotate-secret", s"$OAuthClientPath/revoke").foreach { path =>
        normalizeOAuthProblemResponse(
          requiredObject(root.at(s"/paths/${escape(path)}/post"), s"post $path operation"),
          "409",
          "The optimistic version is stale or the client is not active.",
          oauthLifecycleConflictExamples
        )
      }

      verifyPublishedOAuthSchemas(schemas)
      verifyPublishedOAuthPaths(root)
      root.put(OAuthContractPostprocessedMarker, true)
      ()
    }
  }

  private def preflightOAuthManagementContract(root: ObjectNode): ObjectNode = {
    val responseContracts = Seq(
      (OAuthCollectionPath, "get", "200", OAuthClientPageSchemaName),
      (OAuthCollectionPath, "post", "201", OAuthSecretSchemaName),
      (OAuthClientPath, "get", "200", OAuthViewSchemaName),
      (OAuthClientPath, "patch", "200", OAuthViewSchemaName),
      (s"$OAuthClientPath/audit-events", "get", "200", OAuthAuditPageSchemaName),
      (s"$OAuthClientPath/revoke", "post", "200", OAuthViewSchemaName),
      (s"$OAuthClientPath/rotate-secret", "post", "200", OAuthSecretSchemaName)
    )
    responseContracts.foreach { case (path, method, status, schemaName) =>
      val operation = requiredObject(root.at(s"/paths/${escape(path)}/$method"), s"$method $path operation")
      requireText(operation, s"/responses/$status/content/application~1json/schema/$$ref", s"#/components/schemas/$schemaName")
      verifyExactOAuthBearerSecurity(operation, path, method)
    }
    Seq(
      (OAuthCollectionPath, "post", OAuthCreateSchemaName),
      (OAuthClientPath, "patch", OAuthUpdateSchemaName),
      (s"$OAuthClientPath/rotate-secret", "post", OAuthVersionSchemaName),
      (s"$OAuthClientPath/revoke", "post", OAuthVersionSchemaName)
    ).foreach { case (path, method, schemaName) =>
      val operation = requiredObject(root.at(s"/paths/${escape(path)}/$method"), s"$method $path operation")
      if (!operation.at("/requestBody/required").asBoolean())
        throw new IllegalStateException(s"Expected $method $path request body to be required")
      requireText(operation, "/requestBody/content/application~1json/schema/$ref", s"#/components/schemas/$schemaName")
    }

    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    val published =
      root.path(OAuthContractPostprocessedMarker).isBoolean && root.path(OAuthContractPostprocessedMarker).asBoolean()
    if (published) {
      verifyPublishedOAuthSchemas(schemas)
      verifyPublishedOAuthPaths(root)
    } else {
      verifyRawOAuthStatusParameter(requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/get"), s"get $OAuthCollectionPath operation"))
      verifyRawBaklavaOAuthSchemas(schemas)
    }
    schemas
  }

  private def verifyExactOAuthBearerSecurity(
    operation: ObjectNode,
    path: String,
    method: String
  ): Unit = {
    val security = operation.path("security")
    val exact =
      if (security.isArray && security.size() == 1) {
        val requirement = security.get(0)
        requirement.isObject &&
        requirement.fieldNames().asScala.toSet == Set("bearerAuth") &&
        requirement.path("bearerAuth").isArray &&
        requirement.path("bearerAuth").isEmpty
      } else false
    if (!exact)
      throw new IllegalStateException(s"Expected $method $path security to equal exactly one bearerAuth requirement with empty scopes")
    ()
  }

  private def verifyPublishedOAuthSchemas(schemas: ObjectNode): Unit = {
    val expected = requiredObject(Yaml.mapper().readTree(OAuthComponentSchemasJson), "normalized OAuth component schemas")
    val mismatches = expected
      .properties()
      .asScala
      .collect {
        case entry if schemas.path(entry.getKey) != entry.getValue => entry.getKey
      }
      .toSeq
    if (mismatches.nonEmpty)
      throw new IllegalStateException(s"Published OAuth schemas drifted before idempotent normalization: ${mismatches.mkString(", ")}")
    ()
  }

  private def verifyPublishedOAuthPaths(root: ObjectNode): Unit = {
    val list = requiredObject(root.at(s"/paths/${escape(OAuthCollectionPath)}/get"), s"get $OAuthCollectionPath operation")
    val status =
      uniqueOAuthStatusParameter(list, "published OAuth list", missing = "Expected exactly one published OAuth list status parameter, but found none")
    val expected = requiredObject(
      Yaml
        .mapper()
        .readTree(s"""{"name":"status","in":"query","required":false,"schema":{"type":"string","enum":[${OAuthStatusValues
            .map(value => s"\"$value\"")
            .mkString(",")}]}}"""),
      "published OAuth status query parameter"
    )
    if (status != expected)
      throw new IllegalStateException("Published OAuth list status parameter drifted before idempotent normalization")

    Seq(
      (OAuthCollectionPath, "post", OAuthCreateSchemaName),
      (OAuthClientPath, "patch", OAuthUpdateSchemaName),
      (s"$OAuthClientPath/rotate-secret", "post", OAuthVersionSchemaName),
      (s"$OAuthClientPath/revoke", "post", OAuthVersionSchemaName)
    ).foreach { case (path, method, schemaName) =>
      val actual          = root.at(s"/paths/${escape(path)}/$method/requestBody")
      val expectedRequest = Yaml.mapper().createObjectNode()
      expectedRequest.put("required", true)
      expectedRequest
        .putObject("content")
        .putObject("application/json")
        .set[JsonNode]("schema", refSchema(s"#/components/schemas/$schemaName"))
      if (actual != expectedRequest)
        throw new IllegalStateException(s"Published OAuth $method $path request body drifted before idempotent normalization")
    }

    Seq(
      (OAuthCollectionPath, "get", "200", OAuthClientPageSchemaName, OAuthClientPageEmptyJson, OAuthClientPageExampleJson),
      (OAuthCollectionPath, "post", "201", OAuthSecretSchemaName, OAuthCreateSecretExampleJson, OAuthCreateSecretExampleJson),
      (OAuthClientPath, "get", "200", OAuthViewSchemaName, OAuthExpiredClientExampleJson, OAuthExpiredClientExampleJson),
      (OAuthClientPath, "patch", "200", OAuthViewSchemaName, OAuthUpdatedClientExampleJson, OAuthUpdatedClientExampleJson),
      (s"$OAuthClientPath/audit-events", "get", "200", OAuthAuditPageSchemaName, OAuthAuditPageEmptyJson, OAuthAuditPageExampleJson),
      (s"$OAuthClientPath/revoke", "post", "200", OAuthViewSchemaName, OAuthRevokedClientExampleJson, OAuthRevokedClientExampleJson),
      (s"$OAuthClientPath/rotate-secret", "post", "200", OAuthSecretSchemaName, OAuthRotateSecretExampleJson, OAuthRotateSecretExampleJson)
    ).foreach { case (path, method, statusCode, schemaName, minimal, maximal) =>
      verifyPublishedOAuthResponseContent(root, path, method, statusCode, schemaName, Seq("minimal" -> minimal, "maximal" -> maximal))
    }

    Seq(
      (
        OAuthCollectionPath,
        "post",
        "400",
        "OAuth client policy or expiration is invalid.",
        Seq("invalidExpiration" -> oauthProblemJson("result:oauth-client:invalid-expiration", 400, "OAuth client expiration is invalid"))
      ),
      (OAuthCollectionPath, "post", "409", "An OAuth client with this name already exists.", oauthNameConflictExamples),
      (
        OAuthClientPath,
        "patch",
        "400",
        "OAuth client policy or expiration is invalid.",
        Seq("invalidExpiration" -> oauthProblemJson("result:oauth-client:invalid-expiration", 400, "OAuth client expiration is invalid"))
      ),
      (
        OAuthClientPath,
        "patch",
        "409",
        "The optimistic version is stale, the client is not active, or its new name already exists.",
        oauthPatchConflictExamples
      ),
      (
        s"$OAuthClientPath/rotate-secret",
        "post",
        "409",
        "The optimistic version is stale or the client is not active.",
        oauthLifecycleConflictExamples
      ),
      (s"$OAuthClientPath/revoke", "post", "409", "The optimistic version is stale or the client is not active.", oauthLifecycleConflictExamples)
    ).foreach { case (path, method, statusCode, description, examples) =>
      val response = requiredObject(root.at(s"/paths/${escape(path)}/$method/responses/$statusCode"), s"published OAuth $statusCode response")
      if (!response.path("description").isTextual || response.path("description").asText() != description)
        throw new IllegalStateException(s"Published OAuth $method $path $statusCode description drifted before idempotent normalization")
      verifyPublishedOAuthResponseContent(root, path, method, statusCode, OAuthProblemSchemaName, examples)
    }
    ()
  }

  private def verifyPublishedOAuthResponseContent(
    root: ObjectNode,
    path: String,
    method: String,
    status: String,
    schemaName: String,
    examples: Seq[(String, String)]
  ): Unit = {
    val expectedResponse = Yaml.mapper().createObjectNode()
    replaceJsonResponseContent(expectedResponse, refSchema(s"#/components/schemas/$schemaName"), examples)
    val actual = root.at(s"/paths/${escape(path)}/$method/responses/$status/content")
    if (actual != expectedResponse.path("content"))
      throw new IllegalStateException(s"Published OAuth $method $path $status response content drifted before idempotent normalization")
    ()
  }

  private def verifyRawOAuthStatusParameter(operation: ObjectNode): Unit = {
    val description = "raw Baklava OAuth list status parameter"
    val parameter   = uniqueOAuthStatusParameter(operation, "raw Baklava OAuth list", missing = s"Expected exactly one $description, but found none")

    val location = parameter.path("in")
    if (!location.isTextual || location.asText() != "query")
      throw new IllegalStateException(s"Expected $description in to be query, but found ${displayValue(location)}")

    val required = parameter.path("required")
    if (!required.isBoolean || required.asBoolean())
      throw new IllegalStateException(s"Expected $description required to be false")

    val schema = requiredObject(parameter.path("schema"), s"$description schema")
    if (schema.has("$ref"))
      throw new IllegalStateException(s"Expected $description schema to define an inline string type without $$ref")
    val schemaType = schema.path("type")
    if (!schemaType.isTextual || schemaType.asText() != "string")
      throw new IllegalStateException(s"Expected $description schema type to be string, but found ${displayValue(schemaType)}")
    if (schema.has("enum"))
      requireTextSet(schema.path("enum"), OAuthStatusValues.toSet, s"$description values")
    ()
  }

  private def uniqueOAuthStatusParameter(
    operation: ObjectNode,
    description: String,
    missing: String
  ): ObjectNode = {
    val parameters = operation.path("parameters") match {
      case values: ArrayNode                            => values
      case value if value.isMissingNode || value.isNull => Yaml.mapper().createArrayNode()
      case _                                            => throw new IllegalStateException(s"Expected $description parameters to be an array")
    }
    val matches = parameters.elements().asScala.filter(_.path("name").asText() == "status").toSeq
    matches match {
      case Seq(parameter) => requiredObject(parameter, s"$description status parameter")
      case Seq()          => throw new IllegalStateException(missing)
      case values =>
        throw new IllegalStateException(s"Expected exactly one $description status parameter, but found ${values.size}")
    }
  }

  private def displayValue(node: JsonNode): String =
    if (node.isMissingNode) "<missing>" else node.toString.stripPrefix("\"").stripSuffix("\"")

  private def verifyRawBaklavaOAuthSchemas(schemas: ObjectNode): Unit = {
    val view = requiredObject(schemas.path(OAuthViewSchemaName), "raw Baklava OAuth client view schema")
    verifyRawOAuthClientView(view, "raw Baklava OAuth client view")

    val page = requiredObject(schemas.path(OAuthClientPageSchemaName), "raw Baklava OAuth client page schema")
    verifyRawPage(page, "raw Baklava OAuth client page")
    verifyRawOAuthClientView(
      requiredObject(page.at("/properties/results/items"), "raw Baklava OAuth client page item schema"),
      "raw Baklava OAuth client page item"
    )

    val secret = requiredObject(schemas.path(OAuthSecretSchemaName), "raw Baklava OAuth one-time secret schema")
    verifyRawObjectSchema(secret, Set("client", "clientSecret"), Set("client", "clientSecret"), "raw Baklava OAuth one-time secret")
    verifyRawOAuthClientView(
      requiredObject(secret.at("/properties/client"), "raw Baklava OAuth one-time secret client schema"),
      "raw Baklava OAuth one-time secret client"
    )
    requireText(secret, "/properties/clientSecret/type", "string")

    val auditPage = requiredObject(schemas.path(OAuthAuditPageSchemaName), "raw Baklava OAuth audit page schema")
    verifyRawPage(auditPage, "raw Baklava OAuth audit page")
    verifyRawOAuthAuditEvent(requiredObject(auditPage.at("/properties/results/items"), "raw Baklava OAuth audit event schema"))

    val create = requiredObject(schemas.path(OAuthCreateSchemaName), "raw Baklava OAuth create request schema")
    verifyRawObjectSchema(
      create,
      Set("name", "scopes", "projectAccess", "expiresAt"),
      Set("name", "scopes", "projectAccess"),
      "raw Baklava OAuth create request"
    )
    requireText(create, "/properties/name/type", "string")
    verifyRawOAuthScopes(requiredObject(create.at("/properties/scopes"), "raw Baklava OAuth create scopes"))
    verifyRawOAuthProjectAccess(
      requiredObject(create.at("/properties/projectAccess"), "raw Baklava OAuth create project access"),
      "raw Baklava OAuth create project access"
    )
    verifyRawDateTime(requiredObject(create.at("/properties/expiresAt"), "raw Baklava OAuth create expiresAt"), "create expiresAt")

    val version = requiredObject(schemas.path(OAuthVersionSchemaName), "raw Baklava OAuth version request schema")
    verifyRawObjectSchema(version, Set("expectedVersion"), Set("expectedVersion"), "raw Baklava OAuth version request")
    requireText(version, "/properties/expectedVersion/type", "number")

    val update = requiredObject(schemas.path(OAuthUpdateSchemaName), "raw Baklava OAuth PATCH request schema")
    requireText(update, "/type", "object")
    requireExactFields(
      update,
      Set("type"),
      "raw Baklava OAuth PATCH request (Baklava intentionally emits a free object; runtime decoder tests own absent/null/date-time semantics)"
    )
    ()
  }

  private def verifyRawPage(schema: ObjectNode, description: String): Unit = {
    verifyRawObjectSchema(schema, Set("count", "results"), Set("count", "results"), description)
    requireText(schema, "/properties/count/type", "integer")
    requireText(schema, "/properties/count/format", "int32")
    requireText(schema, "/properties/results/type", "array")
    ()
  }

  private def verifyRawOAuthClientView(schema: ObjectNode, description: String): Unit = {
    verifyRawObjectSchema(schema, RawOAuthViewFields, RawOAuthRequiredViewFields, description)
    Seq("clientId", "name").foreach(field => requireText(schema, s"/properties/$field/type", "string"))
    requireText(schema, "/properties/status/type", "string")
    requireTextSet(schema.at("/properties/status/enum"), OAuthStatusValues.toSet, s"$description status values")
    verifyRawOAuthScopes(requiredObject(schema.at("/properties/scopes"), s"$description scopes"))
    verifyRawOAuthProjectAccess(
      requiredObject(schema.at("/properties/projectAccess"), s"$description project access"),
      s"$description project access"
    )
    Seq("version", "policyVersion", "secretVersion").foreach(field => requireText(schema, s"/properties/$field/type", "number"))
    Seq("serviceAccount", "createdBy", "modifiedBy", "secretRotatedBy", "revokedBy").foreach { field =>
      verifyRawOAuthSummary(requiredObject(schema.at(s"/properties/$field"), s"$description $field"), s"$description $field")
    }
    Seq("createdAt", "modifiedAt", "secretRotatedAt", "expiresAt", "revokedAt", "lastUsedAt").foreach { field =>
      verifyRawDateTime(requiredObject(schema.at(s"/properties/$field"), s"$description $field"), s"$description $field")
    }
    ()
  }

  private def verifyRawOAuthAuditEvent(schema: ObjectNode): Unit = {
    val description = "raw Baklava OAuth audit event"
    verifyRawObjectSchema(schema, RawOAuthAuditFields, RawOAuthRequiredAuditFields, description)
    requireText(schema, "/properties/id/type", "string")
    requireText(schema, "/properties/id/format", "uuid")
    verifyRawDateTime(requiredObject(schema.at("/properties/occurredAt"), s"$description occurredAt"), s"$description occurredAt")
    requireTextSet(schema.at("/properties/actorType/enum"), RawOAuthAuditActorTypes, s"$description actor types")
    requireText(schema, "/properties/actorUserId/type", "string")
    requireText(schema, "/properties/actorUserId/format", "uuid")
    verifyRawOAuthSummary(requiredObject(schema.at("/properties/actor"), s"$description actor"), s"$description actor")
    requireTextSet(schema.at("/properties/eventType/enum"), RawOAuthAuditEventTypes, s"$description event types")
    requireTextSet(schema.at("/properties/outcome/enum"), Set("Failure", "Success"), s"$description outcomes")
    requireText(schema, "/properties/metadata/type", "object")
    ()
  }

  private def verifyRawOAuthScopes(schema: ObjectNode): Unit = {
    requireText(schema, "/type", "array")
    requireText(schema, "/items/type", "string")
    requireTextSet(schema.at("/items/enum"), OAuthScopeValues.toSet, "raw Baklava OAuth scope catalog")
    ()
  }

  private def verifyRawOAuthProjectAccess(schema: ObjectNode, description: String): Unit = {
    verifyRawObjectSchema(schema, Set("mode", "projectIds"), Set("mode"), description)
    requireText(schema, "/properties/mode/type", "string")
    requireTextSet(schema.at("/properties/mode/enum"), Set("AllProjects", "SelectedProjects"), s"$description modes")
    requireText(schema, "/properties/projectIds/type", "array")
    requireText(schema, "/properties/projectIds/items/type", "string")
    requireText(schema, "/properties/projectIds/items/format", "uuid")
    ()
  }

  private def verifyRawOAuthSummary(schema: ObjectNode, description: String): Unit = {
    verifyRawObjectSchema(schema, Set("userId", "displayName"), Set("userId", "displayName"), description)
    requireText(schema, "/properties/userId/type", "string")
    requireText(schema, "/properties/userId/format", "uuid")
    requireText(schema, "/properties/displayName/type", "string")
    ()
  }

  private def verifyRawDateTime(schema: ObjectNode, description: String): Unit = {
    requireText(schema, "/type", "string")
    requireText(schema, "/format", "date-time")
    requireExactFields(schema, Set("type", "format"), s"raw Baklava OAuth $description schema")
    ()
  }

  private def verifyRawObjectSchema(
    schema: ObjectNode,
    properties: Set[String],
    required: Set[String],
    description: String
  ): Unit = {
    requireText(schema, "/type", "object")
    requireTextSet(schema.path("required"), required, s"$description required fields")
    requireExactFields(requiredObject(schema.path("properties"), s"$description properties"), properties, s"$description properties")
    ()
  }

  private def requireExactFields(
    node: ObjectNode,
    expected: Set[String],
    description: String
  ): Unit = {
    val actual = node.fieldNames().asScala.toSet
    if (actual != expected)
      throw new IllegalStateException(
        s"Expected $description fields [${expected.toSeq.sorted.mkString(", ")}], but found [${actual.toSeq.sorted.mkString(", ")}]"
      )
    ()
  }

  private def requireTextSet(
    node: JsonNode,
    expected: Set[String],
    description: String
  ): Unit = {
    val actual = requiredArray(node, description).elements().asScala.map(_.asText()).toSet
    if (actual != expected)
      throw new IllegalStateException(
        s"Expected $description [${expected.toSeq.sorted.mkString(", ")}], but found [${actual.toSeq.sorted.mkString(", ")}]"
      )
    ()
  }

  private def normalizeOAuthStatusParameter(operation: ObjectNode): Unit = {
    val parameters = requiredArray(operation.path("parameters"), "OAuth list parameters")
    val matches    = parameters.elements().asScala.filter(_.path("name").asText() == "status").toSeq
    val parameter = matches match {
      case Seq(existing) => requiredObject(existing, "OAuth status query parameter")
      case Seq()         => throw new IllegalStateException("Expected exactly one OAuth status query parameter, but found none")
      case values        => throw new IllegalStateException(s"Expected exactly one OAuth status query parameter, but found ${values.size}")
    }
    parameter.removeAll()
    parameter.put("name", "status")
    parameter.put("in", "query")
    parameter.put("required", false)
    val schema = parameter.putObject("schema")
    schema.put("type", "string")
    val values = schema.putArray("enum")
    OAuthStatusValues.foreach(values.add)
    ()
  }

  private def normalizeOAuthRequest(operation: ObjectNode, schemaName: String): Unit = {
    val requestBody = operation.putObject("requestBody")
    requestBody.put("required", true)
    val media = requestBody.putObject("content").putObject("application/json")
    media.set[JsonNode]("schema", refSchema(s"#/components/schemas/$schemaName"))
    ()
  }

  private def normalizeOAuthSuccessResponse(
    operation: ObjectNode,
    status: String,
    schemaName: String,
    minimal: String,
    maximal: String
  ): Unit = {
    val response = requiredObject(operation.at(s"/responses/$status"), s"OAuth $status success response")
    replaceJsonResponseContent(response, refSchema(s"#/components/schemas/$schemaName"), Seq("minimal" -> minimal, "maximal" -> maximal))
  }

  private def normalizeOAuthProblemResponse(
    operation: ObjectNode,
    status: String,
    description: String,
    examples: Seq[(String, String)]
  ): Unit = {
    val responses = requiredObject(operation.path("responses"), "OAuth operation responses")
    val response =
      if (responses.has(status)) requiredObject(responses.path(status), s"OAuth $status response")
      else responses.putObject(status)
    response.put("description", description)
    replaceJsonResponseContent(response, refSchema(s"#/components/schemas/$OAuthProblemSchemaName"), examples)
  }

  private val oauthNameConflictExamples: Seq[(String, String)] = Seq(
    "nameConflict" -> oauthProblemJson("result:oauth-client:name-conflict", 409, "OAuth client name already exists")
  )

  private val oauthLifecycleConflictExamples: Seq[(String, String)] = Seq(
    "staleVersion" ->
      oauthProblemJson("result:oauth-client:stale-version", 409, "OAuth client version is stale", Some("\"extension\":{\"currentVersion\":4}")),
    "notActive" -> oauthProblemJson("result:oauth-client:not-active", 409, "OAuth client is not active")
  )

  private val oauthPatchConflictExamples: Seq[(String, String)] = oauthLifecycleConflictExamples ++ oauthNameConflictExamples

  private def oauthProblemJson(
    problemType: String,
    status: Int,
    title: String,
    extension: Option[String] = None
  ): String = {
    val suffix = extension.fold("")(value => s",$value")
    s"""{"type":"$problemType","status":$status,"title":"$title","instance":"cid:openapi"$suffix}"""
  }

  private def normalizePivotContract(root: ObjectNode): Unit = {
    validatePublishedPivotContract()

    val operation = requiredObject(root.at(s"/paths/${escape(PivotPath)}/post"), s"post $PivotPath operation")
    normalizeDateParameter(operation, "dateFrom", required = true)
    normalizeDateParameter(operation, "dateTo", required = false)

    val requestBody = requiredObject(operation.path("requestBody"), s"post $PivotPath request body")
    requestBody.put("required", true)
    requestBody.set[JsonNode]("content", content(PivotRequestSchemaRef, MinimalPivotRequestJson, MaximalPivotRequestJson))

    val responses = requiredObject(operation.path("responses"), s"post $PivotPath responses")
    replaceResponseContent(
      requiredObject(responses.path("200"), "pivot 200 response"),
      refSchema(PivotResponseSchemaRef),
      MinimalPivotResponseJson,
      canonicalResponseJson()
    )

    val invalidOrProblem = Yaml.mapper().createObjectNode()
    val alternatives     = invalidOrProblem.putArray("oneOf")
    alternatives.add(refSchema(PivotInvalidErrorSchemaRef))
    alternatives.add(refSchema(PivotProblemDetailsSchemaRef))
    replaceResponseContent(
      requiredObject(responses.path("400"), "pivot 400 response"),
      invalidOrProblem,
      InvalidConfigurationJson,
      InvalidConfigurationJson
    )
    replaceResponseContent(
      requiredObject(responses.path("403"), "pivot 403 response"),
      refSchema(PivotProblemDetailsSchemaRef),
      ForbiddenProblemJson,
      ForbiddenProblemJson
    )
    replaceTextResponseContent(requiredObject(responses.path("404"), "pivot 404 response"), NotFoundText)
    replaceResponseContent(
      requiredObject(responses.path("422"), "pivot 422 response"),
      refSchema(PivotTooLargeErrorSchemaRef),
      ResultTooLargeJson,
      ResultTooLargeJson
    )

    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    schemas.remove(GeneratedPivotRequestSchemaName)
    schemas.remove(GeneratedPivotResponseSchemaName)
    val normalized = requiredObject(Yaml.mapper().readTree(PivotComponentSchemasJson), "normalized pivot component schemas")
    normalized.properties().asScala.foreach(entry => schemas.set[JsonNode](entry.getKey, entry.getValue))

    verifyPivotContract(root)
  }

  private def normalizePivotExportContract(root: ObjectNode): Unit = {
    val operation = requiredObject(root.at(s"/paths/${escape(PivotExportPath)}/post"), s"post $PivotExportPath operation")
    normalizeFormatParameter(operation)
    normalizeDateParameter(operation, "dateFrom", required = true)
    normalizeDateParameter(operation, "dateTo", required = false)

    val requestBody = requiredObject(operation.path("requestBody"), s"post $PivotExportPath request body")
    requestBody.put("required", true)
    requestBody.set[JsonNode]("content", content(PivotExportRequestSchemaRef, MinimalPivotExportRequestJson, MaximalPivotExportRequestJson))

    val responses = requiredObject(operation.path("responses"), s"post $PivotExportPath responses")
    replaceBinaryExportResponseContent(requiredObject(responses.path("200"), "pivot export 200 response"))
    requiredObject(responses.path("204"), "pivot export 204 response").remove("content")

    val invalidExportOrProblem = Yaml.mapper().createObjectNode()
    val alternatives           = invalidExportOrProblem.putArray("oneOf")
    alternatives.add(refSchema(PivotInvalidErrorSchemaRef))
    alternatives.add(refSchema(PivotInvalidExportErrorSchemaRef))
    alternatives.add(refSchema(PivotProblemDetailsSchemaRef))
    replaceJsonResponseContent(
      requiredObject(responses.path("400"), "pivot export 400 response"),
      invalidExportOrProblem,
      Seq(
        "invalidConfiguration" -> InvalidConfigurationJson,
        "invalidExportRequest" -> InvalidExportRequestJson,
        "malformedRequest" ->
          """{"type":"malformed-request-content","status":400,"title":"The request content was malformed","instance":"cid:ct-534-pivot-export-route"}"""
      )
    )
    replaceResponseContent(
      requiredObject(responses.path("401"), "pivot export 401 response"),
      refSchema(PivotProblemDetailsSchemaRef),
      UnauthorizedProblemJson,
      UnauthorizedProblemJson
    )
    replaceResponseContent(
      requiredObject(responses.path("403"), "pivot export 403 response"),
      refSchema(PivotProblemDetailsSchemaRef),
      ExportForbiddenProblemJson,
      ExportForbiddenProblemJson
    )
    replaceTextResponseContent(requiredObject(responses.path("404"), "pivot export 404 response"), NotFoundText)
    replaceResponseContent(
      requiredObject(responses.path("413"), "pivot export 413 response"),
      refSchema(PivotProblemDetailsSchemaRef),
      PayloadTooLargeProblemJson,
      PayloadTooLargeProblemJson
    )
    replaceResponseContent(
      requiredObject(responses.path("422"), "pivot export 422 response"),
      refSchema(PivotTooLargeErrorSchemaRef),
      ResultTooLargeJson,
      ResultTooLargeJson
    )
    replaceResponseContent(
      requiredObject(responses.path("503"), "pivot export 503 response"),
      refSchema(PivotProblemDetailsSchemaRef),
      ExportOverloadedProblemJson,
      ExportOverloadedProblemJson
    )

    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    schemas.fieldNames().asScala.filter(_.startsWith(GeneratedPivotExportSchemaPrefix)).toSeq.foreach(schemas.remove)
    val normalized = requiredObject(Yaml.mapper().readTree(PivotExportComponentSchemasJson), "normalized pivot export component schemas")
    normalized.properties().asScala.foreach(entry => schemas.set[JsonNode](entry.getKey, entry.getValue))

    verifyPivotExportContract(root)
  }

  private def normalizeFormatParameter(operation: ObjectNode): Unit = {
    val parameters = requiredArray(operation.path("parameters"), "pivot export query parameters")
    val matches    = parameters.elements().asScala.filter(_.path("name").asText() == "format").toSeq
    if (matches.size != 1) throw new IllegalStateException("Expected exactly one format query parameter")
    val parameter = requiredObject(matches.head, "format query parameter")
    if (parameter.path("in").asText() != "query") throw new IllegalStateException("Expected format to be a query parameter")
    if (!parameter.path("required").asBoolean()) throw new IllegalStateException("Expected format to be required")
    val schema = requiredObject(parameter.path("schema"), "format query parameter schema")
    schema.removeAll()
    schema.put("type", "string")
    schema.putArray("enum").add("csv").add("xlsx")
    schema.put("example", "csv")
    ()
  }

  private def validatePublishedPivotContract(): Unit = {
    val schema =
      requiredObject(Yaml.mapper().readTree(Files.readString(PivotPublishedSchemaPath, StandardCharsets.UTF_8)), "published pivot JSON Schema")
    requireText(schema, "/" + "$" + "schema", "https://json-schema.org/draft/2020-12/schema")
    requireText(schema, "/x-route/method", "POST")
    requireText(schema, "/x-route/path", PivotPath)

    val definitions = requiredObject(schema.path("$defs"), "published pivot JSON Schema definitions")
    Seq("request", "response", "invalidConfigurationError", "resultTooLargeError", "coordinate", "pathSegment", "metricValues", "filterMembers")
      .foreach(name => requiredObject(definitions.path(name), s"published pivot $name definition"))
    requireArrayTexts(definitions.at("/pivotKey/enum"), PivotKeys, "published pivot keys")
    requireArrayTexts(definitions.at("/pivotValue/enum"), PivotValues, "published pivot values")
    requireArrayTexts(definitions.at("/coordinateKind/enum"), CoordinateKinds, "published coordinate kinds")
    requireArrayTexts(definitions.at("/violationCode/enum"), ViolationCodes, "published violation codes")
    val ref = "$" + "ref"
    val publishedFacetRefs = requiredArray(definitions.at("/filterMembers/oneOf"), "published typed filter-member variants")
      .elements()
      .asScala
      .map(_.path(ref).asText())
      .toSeq
    val expectedFacetRefs = Seq(
      "userFilterMembers",
      "projectFilterMembers",
      "clientFilterMembers",
      "yearFilterMembers",
      "monthFilterMembers",
      "dateFilterMembers",
      "entryTypeFilterMembers",
      "taskFilterMembers"
    ).map("#/$defs/" + _)
    if (publishedFacetRefs != expectedFacetRefs)
      throw new IllegalStateException("Expected eight ordered published typed filter-member variants")
    Seq(
      "userFilterMembers"      -> "uuidValue",
      "projectFilterMembers"   -> "uuidValue",
      "clientFilterMembers"    -> "clientValue",
      "yearFilterMembers"      -> "yearValue",
      "monthFilterMembers"     -> "monthValue",
      "dateFilterMembers"      -> "dateValue",
      "entryTypeFilterMembers" -> "entryTypeValue",
      "taskFilterMembers"      -> "taskValue"
    ).foreach { case (definition, valueDefinition) =>
      requireText(definitions, s"/$definition/properties/members/items/properties/value/$ref", "#/" + "$" + s"defs/$valueDefinition")
    }
    if (requiredArray(definitions.at("/request/allOf"), "published cross-area constraints").size() != 24)
      throw new IllegalStateException("Expected 24 published cross-area uniqueness constraints")
    ()
  }

  private def normalizeDateParameter(
    operation: ObjectNode,
    name: String,
    required: Boolean
  ): Unit = {
    val parameters = requiredArray(operation.path("parameters"), "pivot query parameters")
    val matches    = parameters.elements().asScala.filter(_.path("name").asText() == name).toSeq
    if (matches.size != 1) throw new IllegalStateException(s"Expected exactly one $name query parameter")
    val parameter = requiredObject(matches.head, s"$name query parameter")
    if (parameter.path("in").asText() != "query") throw new IllegalStateException(s"Expected $name to be a query parameter")
    if (parameter.path("required").asBoolean() != required) throw new IllegalStateException(s"Expected $name required to be $required")
    val schema = requiredObject(parameter.path("schema"), s"$name query parameter schema")
    schema.put("type", "string")
    schema.put("format", "date")
    ()
  }

  private def content(
    schemaRef: String,
    minimal: String,
    maximal: String
  ): ObjectNode = {
    val content = Yaml.mapper().createObjectNode()
    val media   = content.putObject("application/json")
    media.set[JsonNode]("schema", refSchema(schemaRef))
    setExamples(media, minimal, maximal)
    content
  }

  private def replaceResponseContent(
    response: ObjectNode,
    schema: ObjectNode,
    minimal: String,
    maximal: String
  ): Unit = {
    val content = Yaml.mapper().createObjectNode()
    val media   = content.putObject("application/json")
    media.set[JsonNode]("schema", schema)
    setExamples(media, minimal, maximal)
    response.set[JsonNode]("content", content)
    ()
  }

  private def replaceJsonResponseContent(
    response: ObjectNode,
    schema: ObjectNode,
    values: Seq[(String, String)]
  ): Unit = {
    val content  = Yaml.mapper().createObjectNode()
    val media    = content.putObject("application/json")
    val examples = media.putObject("examples")
    media.set[JsonNode]("schema", schema)
    values.foreach { case (name, value) =>
      examples.putObject(name).set[JsonNode]("value", parsedObjectExample(value))
    }
    response.set[JsonNode]("content", content)
    ()
  }

  private def replaceBinaryExportResponseContent(response: ObjectNode): Unit = {
    val content = Yaml.mapper().createObjectNode()
    Seq("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").foreach { mediaType =>
      val schema = content.putObject(mediaType).putObject("schema")
      schema.put("type", "string")
      schema.put("format", "binary")
    }
    response.set[JsonNode]("content", content)
    val disposition = response.putObject("headers").putObject("Content-Disposition")
    disposition.put("description", "Attachment with deterministic pivot_table_<dateFrom>_<dateTo-or-open>.<csv|xlsx> filename.")
    disposition.putObject("schema").put("type", "string")
    ()
  }

  private def setExamples(
    media: ObjectNode,
    minimal: String,
    maximal: String
  ): Unit = {
    val examples = media.putObject("examples")
    examples.putObject("minimal").set[JsonNode]("value", parsedObjectExample(minimal))
    examples.putObject("maximal").set[JsonNode]("value", parsedObjectExample(maximal))
    ()
  }

  private def replaceTextResponseContent(response: ObjectNode, value: String): Unit = {
    val content  = Yaml.mapper().createObjectNode()
    val media    = content.putObject("text/plain")
    val examples = media.putObject("examples")
    media.putObject("schema").put("type", "string")
    examples.putObject("minimal").put("value", value)
    examples.putObject("maximal").put("value", value)
    response.set[JsonNode]("content", content)
    ()
  }

  private def parsedObjectExample(value: String): ObjectNode =
    requiredObject(Yaml.mapper().readTree(value), "pivot JSON example")

  private def refSchema(value: String): ObjectNode = {
    val schema = Yaml.mapper().createObjectNode()
    schema.put("$ref", value)
    schema
  }

  private def canonicalResponseJson(): String = {
    val canonical = Yaml.mapper().readTree(Files.readString(PivotCanonicalResponsePath, StandardCharsets.UTF_8))
    if (!canonical.isObject) throw new IllegalStateException("Expected canonical pivot response to be an object")
    canonical.toString
  }

  private def verifyPivotContract(root: ObjectNode): Unit = {
    val operation = requiredObject(root.at(s"/paths/${escape(PivotPath)}/post"), s"post $PivotPath operation")
    val ref       = "$" + "ref"
    requireText(operation, s"/requestBody/content/application~1json/schema/$ref", PivotRequestSchemaRef)
    requireText(operation, s"/responses/200/content/application~1json/schema/$ref", PivotResponseSchemaRef)
    requireText(operation, s"/responses/400/content/application~1json/schema/oneOf/0/$ref", PivotInvalidErrorSchemaRef)
    requireText(operation, s"/responses/400/content/application~1json/schema/oneOf/1/$ref", PivotProblemDetailsSchemaRef)
    requireText(operation, s"/responses/403/content/application~1json/schema/$ref", PivotProblemDetailsSchemaRef)
    requireText(operation, "/responses/404/content/text~1plain/schema/type", "string")
    requireText(operation, "/responses/404/content/text~1plain/examples/minimal/value", NotFoundText)
    requireText(operation, "/responses/404/content/text~1plain/examples/maximal/value", NotFoundText)
    requireText(operation, s"/responses/422/content/application~1json/schema/$ref", PivotTooLargeErrorSchemaRef)

    val dateFrom = requiredParameter(operation, "dateFrom")
    if (!dateFrom.path("required").asBoolean()) throw new IllegalStateException("Expected dateFrom to be required")
    requireText(dateFrom, "/schema/type", "string")
    requireText(dateFrom, "/schema/format", "date")
    val dateTo = requiredParameter(operation, "dateTo")
    if (dateTo.path("required").asBoolean()) throw new IllegalStateException("Expected dateTo to be optional")
    requireText(dateTo, "/schema/type", "string")
    requireText(dateTo, "/schema/format", "date")

    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    Seq(PivotRequestSchemaRef, PivotResponseSchemaRef, PivotInvalidErrorSchemaRef, PivotTooLargeErrorSchemaRef, PivotProblemDetailsSchemaRef)
      .foreach { reference =>
        val name = reference.stripPrefix("#/components/schemas/")
        requiredObject(schemas.path(name), s"pivot OpenAPI schema $name")
      }
    val problemType = requiredObject(schemas.at("/WorkUnitPivotProblemDetails/properties/type"), "Problem Details type schema")
    requireText(problemType, "/type", "string")
    if (problemType.has("format")) throw new IllegalStateException("Problem Details type must allow relative identifiers")
    requireText(problemType, "/description", "Stable relative or absolute problem type identifier emitted by the platform.")
    if (schemas.has(GeneratedPivotRequestSchemaName) || schemas.has(GeneratedPivotResponseSchemaName))
      throw new IllegalStateException("Generated pivot schemas must be replaced by the published contract")

    requireJsonExample(operation, "/requestBody/content/application~1json/examples/minimal/value", MinimalPivotRequestJson)
    requireJsonExample(operation, "/requestBody/content/application~1json/examples/maximal/value", MaximalPivotRequestJson)
    requireJsonExample(operation, "/responses/200/content/application~1json/examples/maximal/value", canonicalResponseJson())
    requireJsonExample(operation, "/responses/400/content/application~1json/examples/minimal/value", InvalidConfigurationJson)
    requireJsonExample(operation, "/responses/403/content/application~1json/examples/minimal/value", ForbiddenProblemJson)
    requireJsonExample(operation, "/responses/422/content/application~1json/examples/minimal/value", ResultTooLargeJson)
    ()
  }

  private def verifyPivotExportContract(root: ObjectNode): Unit = {
    val operation = requiredObject(root.at(s"/paths/${escape(PivotExportPath)}/post"), s"post $PivotExportPath operation")
    val ref       = "$" + "ref"
    requireText(operation, s"/requestBody/content/application~1json/schema/$ref", PivotExportRequestSchemaRef)
    Seq("text~1csv", "application~1vnd.openxmlformats-officedocument.spreadsheetml.sheet").foreach { mediaType =>
      requireText(operation, s"/responses/200/content/$mediaType/schema/type", "string")
      requireText(operation, s"/responses/200/content/$mediaType/schema/format", "binary")
    }
    if (operation.at("/responses/204").has("content"))
      throw new IllegalStateException("Pivot export 204 response must not define content")
    requireText(operation, s"/responses/400/content/application~1json/schema/oneOf/0/$ref", PivotInvalidErrorSchemaRef)
    requireText(operation, s"/responses/400/content/application~1json/schema/oneOf/1/$ref", PivotInvalidExportErrorSchemaRef)
    requireText(operation, s"/responses/400/content/application~1json/schema/oneOf/2/$ref", PivotProblemDetailsSchemaRef)
    Seq("401", "403", "413", "503").foreach { status =>
      requireText(operation, s"/responses/$status/content/application~1json/schema/$ref", PivotProblemDetailsSchemaRef)
    }
    requireText(operation, "/responses/404/content/text~1plain/schema/type", "string")
    requireText(operation, s"/responses/422/content/application~1json/schema/$ref", PivotTooLargeErrorSchemaRef)

    val format = requiredParameter(operation, "format")
    if (!format.path("required").asBoolean()) throw new IllegalStateException("Expected format to be required")
    requireText(format, "/schema/type", "string")
    requireArrayTexts(format.at("/schema/enum"), Seq("csv", "xlsx"), "pivot export formats")
    Seq("dateFrom" -> true, "dateTo" -> false).foreach { case (name, required) =>
      val date = requiredParameter(operation, name)
      if (date.path("required").asBoolean() != required) throw new IllegalStateException(s"Expected $name required to be $required")
      requireText(date, "/schema/type", "string")
      requireText(date, "/schema/format", "date")
    }

    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    Seq(
      PivotExportRequestSchemaRef,
      PivotExportProjectionSchemaRef,
      PivotExportPresentationSchemaRef,
      PivotInvalidExportErrorSchemaRef,
      "#/components/schemas/WorkUnitPivotExportMemberLabel",
      "#/components/schemas/WorkUnitPivotExportViolationCode"
    ).foreach { reference =>
      requiredObject(schemas.path(reference.stripPrefix("#/components/schemas/")), s"pivot export OpenAPI schema $reference")
    }
    requireText(schemas, s"/WorkUnitPivotExportRequest/properties/query/$ref", PivotRequestSchemaRef)
    requireText(schemas, s"/WorkUnitPivotExportRequest/properties/projection/$ref", PivotExportProjectionSchemaRef)
    requireText(schemas, s"/WorkUnitPivotExportRequest/properties/presentation/$ref", PivotExportPresentationSchemaRef)
    requireArrayTexts(schemas.at("/WorkUnitPivotExportRequest/required"), Seq("query", "presentation"), "pivot export required request properties")
    requireText(schemas, "/WorkUnitPivotExportProjection/type", "object")
    requireText(schemas, "/WorkUnitPivotExportProjection/properties/effectiveDateTo/type", "string")
    requireText(schemas, "/WorkUnitPivotExportProjection/properties/effectiveDateTo/format", "date")
    requireText(schemas, s"/WorkUnitPivotExportMemberLabel/properties/value/$ref", "#/components/schemas/WorkUnitPivotFieldValue")
    if (schemas.at("/WorkUnitPivotExportPresentation/properties/memberLabels/maxItems").asInt() != 72000)
      throw new IllegalStateException("Expected the current 72,000 pivot export member-label limit")
    requireText(
      schemas,
      "/WorkUnitPivotExportPresentation/properties/memberLabels/description",
      "Bounded by maxDisplayValueCellCount x 8 (72,000 identities for the current 9,000-cell limit). Each canonical entry is at most 768 bytes; the canonical request budget is 63,296,000 bytes inside the 67,108,864-byte transport limit."
    )
    val obsolete = schemas.fieldNames().asScala.filter(_.startsWith(GeneratedPivotExportSchemaPrefix)).toSeq
    if (obsolete.nonEmpty)
      throw new IllegalStateException(s"Obsolete flat pivot export schemas remain: ${obsolete.mkString(", ")}")

    requireJsonExample(operation, "/requestBody/content/application~1json/examples/minimal/value", MinimalPivotExportRequestJson)
    requireJsonExample(operation, "/requestBody/content/application~1json/examples/maximal/value", MaximalPivotExportRequestJson)
    requireJsonExample(operation, "/responses/400/content/application~1json/examples/invalidConfiguration/value", InvalidConfigurationJson)
    requireJsonExample(operation, "/responses/400/content/application~1json/examples/invalidExportRequest/value", InvalidExportRequestJson)
    requireJsonExample(operation, "/responses/401/content/application~1json/examples/minimal/value", UnauthorizedProblemJson)
    requireJsonExample(operation, "/responses/403/content/application~1json/examples/minimal/value", ExportForbiddenProblemJson)
    requireJsonExample(operation, "/responses/413/content/application~1json/examples/minimal/value", PayloadTooLargeProblemJson)
    requireJsonExample(operation, "/responses/422/content/application~1json/examples/minimal/value", ResultTooLargeJson)
    requireJsonExample(operation, "/responses/503/content/application~1json/examples/minimal/value", ExportOverloadedProblemJson)
    ()
  }

  private def requiredParameter(operation: ObjectNode, name: String): ObjectNode = {
    val parameters = requiredArray(operation.path("parameters"), "pivot query parameters")
    val matches    = parameters.elements().asScala.filter(_.path("name").asText() == name).toSeq
    if (matches.size != 1) throw new IllegalStateException(s"Expected exactly one $name query parameter")
    requiredObject(matches.head, s"$name query parameter")
  }

  private def requireJsonExample(
    operation: ObjectNode,
    pointer: String,
    expected: String
  ): Unit = {
    val actualJson = operation.at(pointer)
    if (!actualJson.isObject) throw new IllegalStateException(s"Expected JSON object example at $pointer")
    val expectedJson = Yaml.mapper().readTree(expected)
    if (actualJson != expectedJson) throw new IllegalStateException(s"Unexpected JSON example at $pointer")
  }

  private def requireArrayTexts(
    node: JsonNode,
    expected: Seq[String],
    description: String
  ): Unit = {
    val actual = requiredArray(node, description).elements().asScala.map(_.asText()).toSeq
    if (actual != expected)
      throw new IllegalStateException(s"Expected $description [${expected.mkString(", ")}], but found [${actual.mkString(", ")}]")
  }

  private def normalizeBuildInfoSchema(root: ObjectNode): Unit = {
    val schemas = requiredObject(root.at("/components/schemas"), "OpenAPI component schemas")
    val schemaNode = Option(schemas.get(BuildInfoSchemaName)).getOrElse {
      throw new IllegalStateException(s"Missing BuildInfo OpenAPI schema component: $BuildInfoSchemaName")
    }
    val schema = requiredObject(schemaNode, "BuildInfo OpenAPI schema")

    schema.removeAll()
    schema.put("type", "object")
    schema.putArray("required").add("commitSha")
    val commitSha = schema.putObject("properties").putObject("commitSha")
    commitSha.put("type", "string")
    commitSha.put("pattern", "^[0-9a-f]{40}$")
    ()
  }

  private def replaceRepeatableParameter(
    root: ObjectNode,
    path: String,
    method: String,
    name: String,
    examples: Seq[String]
  ): Unit = {
    val parameters = requiredArray(root.at(s"/paths/${escape(path)}/$method/parameters"), s"$method $path query parameters")
    val matches    = parameters.elements().asScala.filter(_.path("name").asText() == name).toSeq
    if (matches.size != 1) throw new IllegalStateException(s"Expected exactly one $name query parameter")

    val parameter = requiredObject(matches.head, s"$name query parameter")
    if (parameter.path("in").asText() != "query") throw new IllegalStateException(s"Expected $name to be a query parameter")
    parameter.put("style", "form")
    parameter.put("explode", true)

    val schema = Yaml.mapper().createObjectNode()
    schema.put("type", "array")
    val items = schema.putObject("items")
    items.put("type", "string")
    items.put("format", "uuid")
    val example = schema.putArray("example")
    examples.foreach(example.add)
    parameter.set[JsonNode]("schema", schema)
    ()
  }

  private def replaceExamples(
    root: ObjectNode,
    mediaTypePointer: String,
    minimal: String,
    maximal: String
  ): Unit = {
    replaceExample(root, mediaTypePointer, "minimal", minimal)
    replaceExample(root, mediaTypePointer, "maximal", maximal)
  }

  private def setExamples(
    root: ObjectNode,
    mediaTypePointer: String,
    minimal: String,
    maximal: String
  ): Unit = {
    val media    = requiredObject(root.at(mediaTypePointer), s"media type at $mediaTypePointer")
    val examples = media.putObject("examples")
    examples.putObject("minimal").put("value", minimal)
    examples.putObject("maximal").put("value", maximal)
    ()
  }

  private def replaceExample(
    root: ObjectNode,
    mediaTypePointer: String,
    name: String,
    value: String
  ): Unit = {
    val example = requiredObject(root.at(s"$mediaTypePointer/examples/$name"), s"$name example at $mediaTypePointer")
    if (!example.path("value").isTextual)
      throw new IllegalStateException(s"Expected $name example value at $mediaTypePointer to be JSON text")
    example.put("value", value)
    ()
  }

  private def requireResponseStatuses(
    root: ObjectNode,
    path: String,
    method: String,
    expected: Seq[String]
  ): Unit = {
    val responses = requiredObject(root.at(s"/paths/${escape(path)}/$method/responses"), s"$method $path responses")
    val actual    = responses.fieldNames().asScala.toSeq.sorted
    if (actual != expected)
      throw new IllegalStateException(s"Expected $method $path response statuses [${expected.mkString(", ")}], but found [${actual.mkString(", ")}]")
  }

  private def requireText(
    root: ObjectNode,
    pointer: String,
    expected: String
  ): Unit = {
    val actual = root.at(pointer)
    if (!actual.isTextual || actual.asText() != expected)
      throw new IllegalStateException(s"Expected $pointer to equal $expected")
  }

  private def requiredObject(node: JsonNode, description: String): ObjectNode = node match {
    case value: ObjectNode => value
    case _                 => throw new IllegalStateException(s"Expected $description to be an object")
  }

  private def requiredArray(node: JsonNode, description: String): ArrayNode = node match {
    case value: ArrayNode => value
    case _                => throw new IllegalStateException(s"Expected $description to be an array")
  }

  private def verifyNoDanglingLocalRefs(root: ObjectNode): Unit = {
    def visit(node: JsonNode, pointer: String): Unit = node match {
      case objectNode: ObjectNode =>
        Option(objectNode.get("$ref")).filter(_.isTextual).map(_.asText()).filter(_.startsWith("#/")) foreach { reference =>
          if (root.at(reference.stripPrefix("#")).isMissingNode)
            throw new IllegalStateException(s"Dangling local OpenAPI reference $reference at $pointer")
        }
        objectNode.properties().asScala.foreach { entry =>
          visit(entry.getValue, s"$pointer/${escape(entry.getKey)}")
        }
      case arrayNode: ArrayNode =>
        arrayNode.elements().asScala.zipWithIndex.foreach { case (value, index) => visit(value, s"$pointer/$index") }
      case _ => ()
    }

    visit(root, "")
  }

  private def escape(value: String): String = value.replace("~", "~0").replace("/", "~1")
}
