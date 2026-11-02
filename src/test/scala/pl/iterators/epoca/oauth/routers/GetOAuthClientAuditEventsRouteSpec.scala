package pl.iterators.epoca.oauth.routers

import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult.Success
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.{OAuthAuditEventView, OAuthUserSummary}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}

import java.time.Instant
import java.util.UUID

class GetOAuthClientAuditEventsRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, PaginatedResult[OAuthAuditEventView]] =
    RouteRepresentation[Unit, PaginatedResult[OAuthAuditEventView]](
      "List OAuth client audit events with pagination.",
      "GET",
      "/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events",
      List(
        RouteParameterRepresentation[Int]("pageSize", false, 20, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  private val auth             = allGenerators[AuthContext].normal.generate
  private val organizationSlug = allGenerators[OrganizationSlug].normal.generate
  private val clientId         = allGenerators[OAuthClientPublicId].normal.generate
  private val routePath = routeRepresentation.path
    .replace("{organizationSlug}", organizationSlug.toString)
    .replace("{clientId}", clientId.toString) + "?pageSize=20&pageNumber=1"

  trait LoggedTestCase extends BaseScope {
    override lazy val oauthClientManagementService: OAuthClientManagementService = vmock[OAuthClientManagementService]
    override protected def oauthManagementEnabled: Boolean                       = true
    override val authContextForTest: Option[AuthContext]                         = Some(auth)
  }

  routeRepresentation.name should {
    "return Ok with paginated audit events and forward pagination" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val pagination     = Some(PageSizeAndNumber(20, 1))
      when(oauthClientManagementService.listAuditEvents(auth, organizationSlug, clientId, pagination))
        .thenReturn(io(Success(responseObject.value)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(oauthClientManagementService).listAuditEvents(auth, organizationSlug, clientId, pagination)
    }

    "serialize safe human and non-human actors with explicit nullable expiration metadata" in new LoggedTestCase {
      val pagination = Some(PageSizeAndNumber(20, 1))
      val actorId    = UserId(UUID.randomUUID())
      val occurredAt = OAuthAuditOccurredAt(Instant.parse("2026-08-13T10:15:30.123456Z"))
      val human = OAuthAuditEventView(
        OAuthAuditEventId(UUID.randomUUID()),
        occurredAt,
        OAuthAuditActorType.Human,
        Some(actorId),
        Some(OAuthUserSummary(actorId, "Archived Superadmin")),
        OAuthAuditEventType.Update,
        OAuthAuditOutcome.Success,
        Json.obj("beforeExpiresAt" -> Json.Null, "afterExpiresAt" -> Json.fromString("2027-01-02T03:04:05Z"))
      )
      val anonymous = human.copy(
        id = OAuthAuditEventId(UUID.randomUUID()),
        actorType = OAuthAuditActorType.Anonymous,
        actorUserId = None,
        actor = None,
        eventType = OAuthAuditEventType.InvalidClient,
        metadata = Json.obj()
      )
      val system = human.copy(
        id = OAuthAuditEventId(UUID.randomUUID()),
        actorType = OAuthAuditActorType.System,
        actorUserId = None,
        actor = None,
        eventType = OAuthAuditEventType.RateLimit,
        metadata = Json.obj()
      )
      val responseObject = PaginatedResult(3, Seq(human, anonymous, system))
      when(oauthClientManagementService.listAuditEvents(auth, organizationSlug, clientId, pagination))
        .thenReturn(io(Success(responseObject)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        val json      = responseAs[Json]
        val results   = json.hcursor.downField("results").focus.flatMap(_.asArray).get
        val humanJson = results.head.hcursor

        (humanJson.downField("actor").focus.flatMap(_.asObject).map(_.keys.toSet) shouldEqual Some(Set("userId", "displayName"))) and
          (humanJson.downField("metadata").downField("beforeExpiresAt").focus shouldEqual Some(Json.Null)) and
          (humanJson.downField("metadata").get[String]("afterExpiresAt") shouldEqual Right("2027-01-02T03:04:05Z")) and
          (results(1).hcursor.downField("actor").focus shouldEqual Some(Json.Null)) and
          (results(2).hcursor.downField("actor").focus shouldEqual Some(Json.Null)) and
          (json.noSpaces.contains("clientSecret") should beFalse) and
          (json.noSpaces.contains("secretHash") should beFalse) and
          (json.noSpaces.contains("email") should beFalse) and
          (json.noSpaces.contains("login") should beFalse) and
          (json.noSpaces.contains("isSuperAdmin") should beFalse) and
          (json.noSpaces.contains("Authorization") should beFalse)
      }

      verify(oauthClientManagementService).listAuditEvents(auth, organizationSlug, clientId, pagination)
    }
  }
}
