package pl.iterators.epoca.oauth.policy

import io.circe.Json
import io.circe.syntax._
import io.swagger.v3.core.util.Yaml
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{OAuthAuditEventView, OAuthClientSecretView, OAuthClientView, OAuthUserSummary}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.OpenApiContractPostProcessor
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.Instant
import java.util.UUID
import scala.util.Try

class OAuthOpenApiRegistryContractSpec extends SpecificationLike with JsonProtocol {
  "generated OpenAPI OAuth protection" should {
    "match the complete default-deny registry and reject a newly protected operation" in {
      val registry = OAuthOperationRegistry.default
      val valid    = document(registry.classifiedProtectedOperations)
      val unknown = OAuthHttpOperation(org.apache.pekko.http.scaladsl.model.HttpMethods.POST, "/organizations/{organizationSlug}/new-protected-route")

      OpenApiContractPostProcessor.verifyOAuthProtectedOperationRegistry(valid)
      val published = Yaml.mapper().readTree(OpenApiContractPostProcessor.publishOAuthOperationContracts(valid))
      val publishedContracts = registry.oauthOperations.map { operation =>
        val escapedPath = operation.http.pathTemplate.replace("~", "~0").replace("/", "~1")
        val node        = published.at(s"/paths/$escapedPath/${operation.http.method.value.toLowerCase}")
        node.path("operationId").asText() -> node.path("x-epoca-oauth-scope").asText()
      }
      val driftMessage = Try(
        OpenApiContractPostProcessor.verifyOAuthProtectedOperationRegistry(document(registry.classifiedProtectedOperations + unknown))
      ).failed.toOption.map(_.getMessage)

      Seq(
        publishedContracts == registry.oauthOperations.map(operation => operation.id -> operation.requiredScope.value),
        driftMessage.exists(_.contains("POST /organizations/{organizationSlug}/new-protected-route"))
      ) shouldEqual Seq(true, true)
    }

    "register all seven OAuth management endpoints as human-only operations" in {
      val expected = Set(
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients"),
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients"),
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients/{clientId}"),
        OAuthHttpOperation(HttpMethods.PATCH, "/organizations/{organizationSlug}/oauth-clients/{clientId}"),
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events"),
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients/{clientId}/rotate-secret"),
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients/{clientId}/revoke")
      )

      expected.map(operation => operation -> OAuthOperationRegistry.default.classify(operation.method, operation.pathTemplate)).forall {
        case (_, OAuthOperationRegistry.OAuthOperationClassification.HumanOnly) => true
        case _                                                                  => false
      } should beTrue
    }

    "serialize exact safe JSON shapes for every management response" in {
      val summary = OAuthUserSummary(UserId(UUID.fromString("33333333-3333-4333-8333-333333333333")), "Contract Actor")
      val client = OAuthClientView(
        OAuthClientPublicId("epoca_oauth_client_v1_contract"),
        "Contract client",
        OAuthClientEffectiveStatus.Expired,
        Set(OAuthScope.ProjectsRead),
        OAuthProjectAccess.AllProjects,
        version = 4L,
        policyVersion = 3L,
        secretVersion = 2L,
        summary.copy(displayName = "Integration Contract client"),
        OAuthClientCreatedAt(Instant.parse("2026-08-01T08:12:30Z")),
        summary,
        OAuthClientModifiedAt(Instant.parse("2026-08-19T11:42:10Z")),
        summary,
        OAuthClientSecretRotatedAt(Instant.parse("2026-08-10T09:00:00Z")),
        summary,
        expiresAt = Some(OAuthClientExpiresAt(Instant.parse("2026-08-18T11:00:00Z"))),
        revokedAt = None,
        revokedBy = None,
        lastUsedAt = None
      )
      val audit = OAuthAuditEventView(
        OAuthAuditEventId(UUID.fromString("44444444-4444-4444-8444-444444444444")),
        OAuthAuditOccurredAt(Instant.parse("2026-08-19T11:42:10Z")),
        OAuthAuditActorType.Anonymous,
        actorUserId = None,
        actor = None,
        OAuthAuditEventType.Update,
        OAuthAuditOutcome.Success,
        Json.obj("beforeExpiresAt" -> Json.Null, "afterExpiresAt" -> Json.fromString("2026-12-31T22:59:00Z"))
      )
      val publicFields = Set(
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
      val forbiddenFields =
        Set("clientSecret", "secretHash", "accessToken", "authorization", "email", "login", "roles", "isSuperAdmin", "technicalIdentity")
      val clientPageJson = PaginatedResult(1, Seq(client)).asJson
      val humanAudit     = audit.copy(actorType = OAuthAuditActorType.Human, actorUserId = Some(summary.userId), actor = Some(summary))
      val auditPageJson  = PaginatedResult(2, Seq(audit, humanAudit)).asJson
      val ordinary = Map(
        "list"   -> clientPageJson.hcursor.downField("results").downArray.focus.get,
        "get"    -> client.asJson,
        "update" -> client.asJson,
        "revoke" -> client.asJson
      )
      val secrets        = Seq(OAuthClientSecretView(client, "one-time-create").asJson, OAuthClientSecretView(client, "one-time-rotate").asJson)
      val auditJson      = auditPageJson.hcursor.downField("results").downArray.focus.get
      val humanAuditJson = auditPageJson.hcursor.downField("results").downN(1).focus.get

      clientPageJson.asObject.map(_.keys.toSet) shouldEqual Some(Set("count", "results"))
      clientPageJson.hcursor.get[Int]("count") shouldEqual Right(1)
      clientPageJson.hcursor.downField("results").focus.flatMap(_.asArray).map(_.toSeq) shouldEqual Some(Seq(client.asJson))
      auditPageJson.asObject.map(_.keys.toSet) shouldEqual Some(Set("count", "results"))
      auditPageJson.hcursor.get[Int]("count") shouldEqual Right(2)
      auditPageJson.hcursor.downField("results").focus.flatMap(_.asArray).map(_.toSeq) shouldEqual Some(Seq(audit.asJson, humanAudit.asJson))

      ordinary.values.foreach { json =>
        json.asObject.map(_.keys.toSet) shouldEqual Some(publicFields)
        Seq("serviceAccount", "createdBy", "modifiedBy", "secretRotatedBy").foreach { field =>
          json.hcursor.downField(field).focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual Some(Set("userId", "displayName"))
        }
        Seq("revokedAt", "revokedBy", "lastUsedAt").foreach(field => json.hcursor.downField(field).focus shouldEqual Some(Json.Null))
        forbiddenFields.intersect(allObjectKeys(json)) should beEmpty
      }
      secrets.foreach { json =>
        json.asObject.map(_.keys.toSet) shouldEqual Some(Set("client", "clientSecret"))
        json.hcursor.downField("client").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual Some(publicFields)
        forbiddenFields.intersect(allObjectKeys(json)).filterNot(_ == "clientSecret") should beEmpty
      }
      auditJson.asObject.map(_.keys.toSet) shouldEqual Some(
        Set("id", "occurredAt", "actorType", "actorUserId", "actor", "eventType", "outcome", "metadata")
      )
      auditJson.hcursor.downField("actorUserId").focus shouldEqual Some(Json.Null)
      auditJson.hcursor.downField("actor").focus shouldEqual Some(Json.Null)
      humanAuditJson.hcursor.downField("actor").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual Some(Set("userId", "displayName"))
      auditJson.hcursor.downField("metadata").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual
        Some(Set("beforeExpiresAt", "afterExpiresAt"))
      forbiddenFields.intersect(allObjectKeys(auditJson)) should beEmpty
      forbiddenFields.intersect(allObjectKeys(humanAuditJson)) should beEmpty
      forbiddenFields.intersect(allObjectKeys(clientPageJson)) should beEmpty
      forbiddenFields.intersect(allObjectKeys(auditPageJson)) should beEmpty
    }
  }

  private def allObjectKeys(json: Json): Set[String] =
    json.arrayOrObject(Set.empty, _.iterator.flatMap(allObjectKeys).toSet, fields => fields.keys.toSet ++ fields.values.flatMap(allObjectKeys))

  private def document(operations: Set[OAuthHttpOperation]): String = {
    val paths = operations.toVector
      .groupBy(_.pathTemplate)
      .toVector
      .sortBy(_._1)
      .map { case (path, pathOperations) =>
        val methods = pathOperations
          .sortBy(_.method.value)
          .map { operation =>
            s"""    ${operation.method.value.toLowerCase}:
           |      security:
           |      - bearerAuth: []
           |      responses: {}
           |""".stripMargin
          }
          .mkString
        s"  $path:\n$methods"
      }
      .mkString

    s"""openapi: 3.0.0
       |paths:
       |$paths""".stripMargin
  }
}
