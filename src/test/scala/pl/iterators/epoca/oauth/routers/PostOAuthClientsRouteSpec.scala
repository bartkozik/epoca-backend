package pl.iterators.epoca.oauth.routers

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.PathMatcher
import org.mockito.Mockito.{clearInvocations, verifyNoInteractions}
import pl.iterators.baklava.core.model.{RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditCorrelationId, OAuthClientExpiresAt, OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.{InvalidExpiration, InvalidPolicy, Success}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{OAuthClientCreateRequest, OAuthClientSecretView, OAuthClientVersionRequest}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

import java.time.Instant
import scala.annotation.nowarn
import scala.jdk.CollectionConverters._

private[routers] object OAuthClientRouteSpecSupport {
  val CorrelationId = "oauth-client-route-spec"

  def problem(
    status: StatusCode,
    typeTag: String,
    title: String,
    extension: Option[Json] = None
  ): Json =
    Json
      .obj(
        "type"      -> Json.fromString(s"result:oauth-client:$typeTag"),
        "status"    -> Json.fromInt(status.intValue),
        "title"     -> Json.fromString(title),
        "instance"  -> Json.fromString(s"cid:$CorrelationId"),
        "extension" -> extension.getOrElse(Json.Null)
      )
      .dropNullValues
}

class PostOAuthClientsRouteSpec extends RouteDocSpec {
  import OAuthClientRouteSpecSupport._

  private val request = OAuthClientCreateRequest("Reporting warehouse", Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
  @nowarn("cat=unused-params")
  override val routeRepresentation: RouteRepresentation[OAuthClientCreateRequest, OAuthClientSecretView] =
    RouteRepresentation[OAuthClientCreateRequest, OAuthClientSecretView](
      "Create an OAuth client and return its secret exactly once.",
      "POST",
      "/organizations/{organizationSlug}/oauth-clients",
      requestPredefinedValue = Some(request),
      authentication = List(RouteSecurity.Bearer())
    )

  private val auth             = allGenerators[AuthContext].normal.generate
  private val organizationSlug = allGenerators[OrganizationSlug].normal.generate
  private val routePath        = routeRepresentation.path.replace("{organizationSlug}", organizationSlug)

  trait LoggedTestCase extends BaseScope {
    override lazy val oauthClientManagementService: OAuthClientManagementService = vmock[OAuthClientManagementService]
    override protected def oauthManagementEnabled: Boolean                       = true
    override val authContextForTest: Option[AuthContext]                         = Some(auth)
  }

  trait LoggingTestCase extends LoggedTestCase {
    override lazy val logger: org.slf4j.Logger = {
      val captured = mock[org.slf4j.Logger]
      when(captured.isInfoEnabled).thenReturn(true)
      captured
    }
    override lazy val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ "api" / "v1"

    def loggedMessages: String =
      mockingDetails(logger).getInvocations.asScala.flatMap(_.getArguments.collect { case value: String => value }).mkString("\n")
  }

  trait NoLoggedTestCase extends BaseScope {
    override protected def oauthManagementEnabled: Boolean = true
    override val authContextForTest: Option[AuthContext]   = None
  }

  trait DisabledTestCase extends BaseScope {
    override protected def oauthManagementEnabled: Boolean = false
    override lazy val oauthClientManagementService: OAuthClientManagementService =
      throw new AssertionError("disabled management routes must not evaluate management dependencies")
    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible without a human session" in new NoLoggedTestCase {
      TestRequest(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "be unavailable when OAuth is disabled without evaluating management dependencies" in new DisabledTestCase {
      TestRequest(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
        responseAs[Json] shouldEqual Json.obj(
          "type"     -> Json.fromString("rejection:route-not-found"),
          "status"   -> Json.fromInt(StatusCodes.NotFound.intValue),
          "title"    -> Json.fromString("Route not found"),
          "instance" -> Json.fromString(s"cid:$CorrelationId")
        )
      }
    }

    "return 201 with the one-time secret DTO" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val correlationId  = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.create(auth, organizationSlug, request, correlationId)).thenReturn(io(Success(responseObject.value)))

      TestRequest(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(oauthClientManagementService).create(auth, organizationSlug, request, correlationId)
    }

    "return the stable invalid-policy problem" in new LoggedTestCase {
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.create(auth, organizationSlug, request, correlationId)).thenReturn(io(InvalidPolicy))

      TestRequest(routePath, requestJson(request.asJson.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-policy", "OAuth client policy is invalid")
      }

      verify(oauthClientManagementService).create(auth, organizationSlug, request, correlationId)
    }

    "return the stable invalid-policy problem for an unknown scope" in new LoggedTestCase {
      val invalidRequest = request.asJson.mapObject(_.add("scopes", Json.arr(Json.fromString("unknown:scope"))))

      TestRequest(routePath, requestJson(invalidRequest.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-policy", "OAuth client policy is invalid")
      }

      verifyNoInteractions(oauthClientManagementService)
    }

    "decode absent, null, and valid expiration values exactly" in new LoggedTestCase {
      val future        = OAuthClientExpiresAt(Instant.parse("2037-01-02T03:04:05Z"))
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      val baseJson      = request.asJson.mapObject(_.remove("expiresAt"))
      val cases = Seq(
        baseJson                                                                 -> request.copy(expiresAt = None),
        baseJson.mapObject(_.add("expiresAt", Json.Null))                        -> request.copy(expiresAt = None),
        baseJson.mapObject(_.add("expiresAt", Json.fromString(future.toString))) -> request.copy(expiresAt = Some(future))
      )
      cases.foreach { case (json, decoded) =>
        val responseObject = routeRepresentation.response.random.value
        when(oauthClientManagementService.create(auth, organizationSlug, decoded, correlationId)).thenReturn(io(Success(responseObject)))

        TestRequest(routePath, requestJson(json.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.Created
        }

        verify(oauthClientManagementService).create(auth, organizationSlug, decoded, correlationId)
        clearInvocations(oauthClientManagementService)
      }
    }

    "classify only malformed expiration members as invalid-expiration" in new LoggedTestCase {
      val baseJson = request.asJson.mapObject(_.remove("expiresAt"))
      Seq(Json.fromString("tomorrow"), Json.fromInt(123)).foreach { expiration =>
        TestRequest(routePath, requestJson(baseJson.mapObject(_.add("expiresAt", expiration)).noSpaces)) ~>
          addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
            response.status shouldEqual StatusCodes.BadRequest
            responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-expiration", "OAuth client expiration is invalid")
          }
      }

      verifyNoInteractions(oauthClientManagementService)
    }

    "return invalid-expiration when a well-formed expiration is not future" in new LoggedTestCase {
      val expiration    = OAuthClientExpiresAt(Instant.parse("2020-01-02T03:04:05Z"))
      val expiring      = request.copy(expiresAt = Some(expiration))
      val correlationId = Some(OAuthAuditCorrelationId(CorrelationId))
      when(oauthClientManagementService.create(auth, organizationSlug, expiring, correlationId)).thenReturn(io(InvalidExpiration))

      TestRequest(routePath, requestJson(expiring.asJson.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-expiration", "OAuth client expiration is invalid")
      }

      verify(oauthClientManagementService).create(auth, organizationSlug, expiring, correlationId)
    }

    "return the stable invalid-policy problem when AllProjects contains projectIds" in new LoggedTestCase {
      val invalidProjectAccess = Json.obj("mode" -> Json.fromString("AllProjects"), "projectIds" -> Json.arr())
      val invalidRequest       = request.asJson.mapObject(_.add("projectAccess", invalidProjectAccess))

      TestRequest(routePath, requestJson(invalidRequest.noSpaces)) ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        responseAs[Json] shouldEqual problem(StatusCodes.BadRequest, "invalid-policy", "OAuth client policy is invalid")
      }

      verifyNoInteractions(oauthClientManagementService)
    }

    "redact exact create and rotate request and response entities from success and failure logs" in new LoggingTestCase {
      val secretSentinel   = "oauth-client-secret-sentinel"
      val hashSentinel     = "oauth-secret-hash-sentinel"
      val tokenSentinel    = "oauth-access-token-sentinel"
      val keySentinel      = "oauth-signing-key-sentinel"
      val basicSentinel    = "oauth-basic-sentinel"
      val sensitiveRequest = request.copy(name = Seq(hashSentinel, tokenSentinel, keySentinel).mkString("-"))
      val responseObject   = routeRepresentation.response.random.value.copy(clientSecret = secretSentinel)
      val clientId         = responseObject.client.clientId
      val rotateRequest    = OAuthClientVersionRequest(responseObject.client.version)
      val correlationId    = Some(OAuthAuditCorrelationId(CorrelationId))
      val productionPath   = s"/api/v1$routePath"
      when(oauthClientManagementService.create(auth, organizationSlug, sensitiveRequest, correlationId))
        .thenReturn(io(Success(responseObject)), io(InvalidPolicy))
      when(oauthClientManagementService.rotateSecret(auth, organizationSlug, clientId, rotateRequest, correlationId))
        .thenReturn(io(Success(responseObject)), io(InvalidPolicy))
      val authorization = RawHeader("Authorization", s"Basic $basicSentinel")

      Seq(StatusCodes.Created, StatusCodes.BadRequest).foreach { expectedStatus =>
        TestRequest(productionPath, requestJson(sensitiveRequest.asJson.noSpaces)) ~>
          authorization ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check(response.status shouldEqual expectedStatus)
      }
      val rotatePath = s"$productionPath/$clientId/rotate-secret"
      Seq(StatusCodes.OK, StatusCodes.BadRequest).foreach { expectedStatus =>
        TestRequest(rotatePath, requestJson(rotateRequest.asJson.noSpaces)) ~>
          authorization ~> addHeader("Correlation-Id", CorrelationId) ~> allRoutes ~> check(response.status shouldEqual expectedStatus)
      }

      val logs = loggedMessages
      verify(oauthClientManagementService, times(2)).create(auth, organizationSlug, sensitiveRequest, correlationId)
      verify(oauthClientManagementService, times(2)).rotateSecret(auth, organizationSlug, clientId, rotateRequest, correlationId)
      (logs.contains(secretSentinel) should beFalse) and
        (logs.contains(hashSentinel) should beFalse) and
        (logs.contains(tokenSentinel) should beFalse) and
        (logs.contains(keySentinel) should beFalse) and
        (logs.contains(basicSentinel) should beFalse) and
        ("""entity=\[[^\]]*\*\*\*\]""".r.findFirstIn(logs).isDefined should beTrue)
    }
  }
}
