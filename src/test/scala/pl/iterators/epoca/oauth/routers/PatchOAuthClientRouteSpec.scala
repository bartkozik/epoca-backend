package pl.iterators.epoca.oauth.routers

import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{DecodingFailure, Json}
import json.Schema
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditCorrelationId, OAuthClientExpiresAt, OAuthClientPublicId}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.kebs.jsonschema.JsonSchemaWrapper

import java.time.Instant
import scala.annotation.nowarn

class PatchOAuthClientRouteSpec extends RouteDocSpec {
  import OAuthClientRouteSpecSupport._

  private val request = OAuthClientUpdateRequest(1L, Some("Renamed"), None, None)
  private implicit val requestSchema: JsonSchemaWrapper[OAuthClientUpdateRequest] =
    JsonSchemaWrapper(Schema.`object`.Free[OAuthClientUpdateRequest]())

  @nowarn("cat=unused-params")
  override val routeRepresentation: RouteRepresentation[OAuthClientUpdateRequest, OAuthClientView] =
    RouteRepresentation[OAuthClientUpdateRequest, OAuthClientView](
      "Replace supplied OAuth client fields using optimistic concurrency.",
      "PATCH",
      "/organizations/{organizationSlug}/oauth-clients/{clientId}",
      requestPredefinedValue = Some(request),
      authentication = List(RouteSecurity.Bearer())
    )

  private val auth             = allGenerators[AuthContext].normal.generate
  private val organizationSlug = allGenerators[OrganizationSlug].normal.generate
  private val clientId         = allGenerators[OAuthClientPublicId].normal.generate
  private val routePath        = routeRepresentation.path.replace("{organizationSlug}", organizationSlug).replace("{clientId}", clientId.toString)

  trait LoggedTestCase extends BaseScope {
    override lazy val oauthClientManagementService: OAuthClientManagementService = vmock[OAuthClientManagementService]
    override protected def oauthManagementEnabled: Boolean                       = true
    override val authContextForTest: Option[AuthContext]                         = Some(auth)
  }

  private def stableProblemExample(
    result: OAuthClientManagementResult[OAuthClientView],
    status: StatusCode,
    typeTag: String,
    title: String
  ) =
    s"return the stable $typeTag problem" in new LoggedTestCase {
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.update(auth, organizationSlug, clientId, request, correlationId))
        .thenReturn(io(result))

      TestRequest(routePath, requestJson(encodedRequest(request).noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual status
        responseAs[Json] shouldEqual problem(status, typeTag, title)
      }

      verify(oauthClientManagementService).update(auth, organizationSlug, clientId, request, correlationId)
    }

  routeRepresentation.name should {
    "return Ok with the updated public view" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val correlationId  = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.update(auth, organizationSlug, clientId, request, correlationId))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(routePath, requestJson(encodedRequest(request).noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
        responseAs[Json].noSpaces.contains("clientSecret") must beFalse
        responseAs[Json].noSpaces.contains("secretHash") must beFalse
      }

      verify(oauthClientManagementService).update(auth, organizationSlug, clientId, request, correlationId)
    }

    "return currentVersion in the stable stale-version problem" in new LoggedTestCase {
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.update(auth, organizationSlug, clientId, request, correlationId))
        .thenReturn(io(StaleVersion(7)))

      TestRequest(routePath, requestJson(encodedRequest(request).noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        responseAs[Json] shouldEqual problem(
          StatusCodes.Conflict,
          "stale-version",
          "OAuth client version is stale",
          Some(Json.obj("currentVersion" -> Json.fromLong(7)))
        )
      }

      verify(oauthClientManagementService).update(auth, organizationSlug, clientId, request, correlationId)
    }

    stableProblemExample(OrganizationNotFound, StatusCodes.NotFound, "organization-not-found", "Organization not found")
    stableProblemExample(ClientNotFound, StatusCodes.NotFound, "client-not-found", "OAuth client not found")
    stableProblemExample(ProjectNotFound, StatusCodes.NotFound, "project-not-found", "Project not found")
    stableProblemExample(OperationNotPermitted, StatusCodes.Forbidden, "operation-not-permitted", "OAuth client operation not permitted")
    stableProblemExample(InvalidPolicy, StatusCodes.BadRequest, "invalid-policy", "OAuth client policy is invalid")
    stableProblemExample(InvalidExpiration, StatusCodes.BadRequest, "invalid-expiration", "OAuth client expiration is invalid")
    stableProblemExample(NameConflict, StatusCodes.Conflict, "name-conflict", "OAuth client name already exists")
    stableProblemExample(NotActive, StatusCodes.Conflict, "not-active", "OAuth client is not active")

    "decode absent, null, and valid expiration patches exactly" in new LoggedTestCase {
      val future        = OAuthClientExpiresAt(Instant.parse("2037-01-02T03:04:05Z"))
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      val baseJson      = Json.obj("expectedVersion" -> Json.fromLong(1L), "name" -> Json.fromString("Renamed"))
      val cases = Seq(
        baseJson                                                                 -> OAuthExpirationPatch.Unchanged,
        baseJson.mapObject(_.add("expiresAt", Json.Null))                        -> OAuthExpirationPatch.Clear,
        baseJson.mapObject(_.add("expiresAt", Json.fromString(future.toString))) -> OAuthExpirationPatch.Set(future)
      )
      cases.foreach { case (json, expiration) =>
        val decoded        = OAuthClientUpdateRequest(1L, Some("Renamed"), None, None, expiration)
        val responseObject = routeRepresentation.response.random.value
        when(oauthClientManagementService.update(auth, organizationSlug, clientId, decoded, correlationId)).thenReturn(io(Success(responseObject)))

        TestRequest(routePath, requestJson(json.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.OK
        }

        verify(oauthClientManagementService).update(auth, organizationSlug, clientId, decoded, correlationId)
      }
    }

    "classify only malformed expiration patches as invalid-expiration" in new LoggedTestCase {
      val baseJson = Json.obj("expectedVersion" -> Json.fromLong(1L), "name" -> Json.fromString("Renamed"))
      Seq(Json.fromString("tomorrow"), Json.fromInt(123)).foreach { expiration =>
        TestRequest(routePath, requestJson(baseJson.mapObject(_.add("expiresAt", expiration)).noSpaces)) ~>
          addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
            response.status shouldEqual StatusCodes.BadRequest
            responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-expiration", "OAuth client expiration is invalid")
          }
      }

      verifyNoInteractions(oauthClientManagementService)
    }

    "pass a non-future but well-formed expiration to the service" in new LoggedTestCase {
      val expiration    = OAuthClientExpiresAt(Instant.parse("2020-01-02T03:04:05Z"))
      val requestObject = OAuthClientUpdateRequest(1L, None, None, None, OAuthExpirationPatch.Set(expiration))
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.update(auth, organizationSlug, clientId, requestObject, correlationId)).thenReturn(io(InvalidExpiration))

      TestRequest(
        routePath,
        requestJson(Json.obj("expectedVersion" -> Json.fromLong(1L), "expiresAt" -> Json.fromString(expiration.toString)).noSpaces)
      ) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-expiration", "OAuth client expiration is invalid")
      }

      verify(oauthClientManagementService).update(auth, organizationSlug, clientId, requestObject, correlationId)
    }
  }

  private def encodedRequest(value: OAuthClientUpdateRequest): Json =
    Json.fromJsonObject(OAuthClientUpdateRequest.encoder.encodeObject(value))
}

class OAuthClientExpirationDecoderSpec extends org.specs2.mutable.Specification {
  private val future = OAuthClientExpiresAt(Instant.parse("2037-01-02T03:04:05Z"))
  private val createBase =
    """{"name":"Reporting","scopes":["projects:read"],"projectAccess":{"mode":"AllProjects"}}"""

  "OAuth client expiration decoders" should {
    "decode create absence, null, and a valid offset timestamp" in {
      val absent   = decode[OAuthClientCreateRequest](createBase)
      val nulled   = decode[OAuthClientCreateRequest](createBase.dropRight(1) + ",\"expiresAt\":null}")
      val offset   = decode[OAuthClientCreateRequest](createBase.dropRight(1) + ",\"expiresAt\":\"2037-01-02T04:04:05+01:00\"}")
      val nanos    = decode[OAuthClientCreateRequest](createBase.dropRight(1) + ",\"expiresAt\":\"2037-01-02T03:04:05.123456789Z\"}")
      val rawNanos = OAuthClientExpiresAt(Instant.parse("2037-01-02T03:04:05.123456789Z"))

      (absent.map(_.expiresAt) shouldEqual Right(None)) and
        (nulled.map(_.expiresAt) shouldEqual Right(None)) and
        (offset.map(_.expiresAt) shouldEqual Right(Some(future))) and
        (offset.flatMap(_.asJson.hcursor.get[String]("expiresAt")) shouldEqual Right("2037-01-02T03:04:05Z")) and
        (nanos.map(_.expiresAt) shouldEqual Right(Some(rawNanos)))
    }

    "decode patch key presence as unchanged, clear, or set" in {
      val absent           = decode[OAuthClientUpdateRequest]("""{"expectedVersion":1}""")
      val nulled           = decode[OAuthClientUpdateRequest]("""{"expectedVersion":1,"expiresAt":null}""")
      val set              = decode[OAuthClientUpdateRequest]("""{"expectedVersion":1,"expiresAt":"2037-01-02T03:04:05Z"}""")
      val offset           = decode[OAuthClientUpdateRequest]("""{"expectedVersion":1,"expiresAt":"2037-01-02T04:04:05+01:00"}""")
      val outOfRange       = decode[OAuthClientUpdateRequest]("""{"expectedVersion":1,"expiresAt":"+10000-01-01T00:00:00Z"}""")
      val parsedOutOfRange = OAuthExpirationPatch.Set(OAuthClientExpiresAt(Instant.parse("+10000-01-01T00:00:00Z")))

      (absent.map(_.expiresAt) shouldEqual Right(OAuthExpirationPatch.Unchanged)) and
        (nulled.map(_.expiresAt) shouldEqual Right(OAuthExpirationPatch.Clear)) and
        (set.map(_.expiresAt) shouldEqual Right(OAuthExpirationPatch.Set(future))) and
        (offset.map(_.expiresAt) shouldEqual Right(OAuthExpirationPatch.Set(future))) and
        (offset.flatMap(_.asJson.hcursor.get[String]("expiresAt")) shouldEqual Right("2037-01-02T03:04:05Z")) and
        (outOfRange.map(_.expiresAt) shouldEqual Right(parsedOutOfRange))
    }

    "classify only malformed or wrongly typed expiresAt members as expiration failures" in {
      val malformed = parse(createBase.dropRight(1) + ",\"expiresAt\":\"tomorrow\"}").flatMap(_.as[OAuthClientCreateRequest])
      val wrongType = parse("""{"expectedVersion":1,"expiresAt":123}""").flatMap(_.as[OAuthClientUpdateRequest])
      val badScope = parse("""{"name":"Reporting","scopes":["unknown"],"projectAccess":{"mode":"AllProjects"}}""")
        .flatMap(_.as[OAuthClientCreateRequest])
      val nonObject = parse("[]").flatMap(_.as[OAuthClientUpdateRequest])

      (isExpirationFailure(malformed) should beTrue) and
        (isExpirationFailure(wrongType) should beTrue) and
        (isExpirationFailure(badScope) should beFalse) and
        (isExpirationFailure(nonObject) should beFalse)
    }
  }

  private def decode[A: io.circe.Decoder](value: String): Either[io.circe.Error, A] =
    parse(value).flatMap(_.as[A])

  private def isExpirationFailure(result: Either[io.circe.Error, _]): Boolean =
    result match {
      case Left(failure: DecodingFailure) => OAuthExpirationDecodingFailure.unapply(failure)
      case _                              => false
    }
}
