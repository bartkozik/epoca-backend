package pl.iterators.epoca.oauth.routers

import cats.effect.unsafe.IORuntime
import io.circe.{Decoder, Encoder, Json}
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthAuditCorrelationId, OAuthClientEffectiveStatus, OAuthClientPublicId}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.http.{BaseRouter, Error}
import pl.iterators.epoca.utils.log.TracingContext

import java.net.URI

final class OAuthClientRouter(oauthClientManagementService: => OAuthClientManagementService)(implicit runtime: IORuntime) extends BaseRouter {
  import OAuthClientRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(OrganizationsPath / Segment.as[OrganizationSlug] / OAuthClientsPath) { organizationSlug =>
      (pathEndOrSingleSlash & get & pagination & parameter("status".as[OAuthClientEffectiveStatus].?)) { (pagination, status) =>
        complete(oauthClientManagementService.list(auth, organizationSlug, status, pagination))(managementResponse(StatusCodes.OK))
      } ~
        (pathEndOrSingleSlash & post) {
          policyEntity(OAuthClientCreateRequest.decoder) { request =>
            complete(oauthClientManagementService.create(auth, organizationSlug, request, correlationId))(managementResponse(StatusCodes.Created))
          }
        } ~
        pathPrefix(Segment.as[OAuthClientPublicId]) { clientId =>
          (pathEndOrSingleSlash & get) {
            complete(oauthClientManagementService.get(auth, organizationSlug, clientId))(managementResponse(StatusCodes.OK))
          } ~
            (pathEndOrSingleSlash & patch) {
              policyEntity(OAuthClientUpdateRequest.decoder) { request =>
                complete(oauthClientManagementService.update(auth, organizationSlug, clientId, request, correlationId))(
                  managementResponse(StatusCodes.OK)
                )
              }
            } ~
            (path(RotateSecretPath) & post & entity(as[OAuthClientVersionRequest])) { request =>
              complete(oauthClientManagementService.rotateSecret(auth, organizationSlug, clientId, request, correlationId))(
                managementResponse(StatusCodes.OK)
              )
            } ~
            (path(RevokePath) & post & entity(as[OAuthClientVersionRequest])) { request =>
              complete(oauthClientManagementService.revoke(auth, organizationSlug, clientId, request, correlationId))(
                managementResponse(StatusCodes.OK)
              )
            } ~
            (path(AuditEventsPath) & get & pagination) { pagination =>
              complete(oauthClientManagementService.listAuditEvents(auth, organizationSlug, clientId, pagination))(managementResponse(StatusCodes.OK))
            }
        }
    }

  def unavailableRoutes(implicit tracingContext: TracingContext): Route =
    pathPrefix(OrganizationsPath / Segment.as[OrganizationSlug] / OAuthClientsPath) { _ =>
      val problem = Error[Unit](
        Some(URI.create("rejection:route-not-found")),
        Some(StatusCodes.NotFound),
        Some("Route not found"),
        instance = Some(URI.create(s"cid:${tracingContext.correlationId}"))
      )
      complete(StatusCodes.NotFound -> Error.encoder(problem))
    }

  private def policyEntity[A](decoder: Decoder[A])(f: A => Route)(implicit tracingContext: TracingContext): Route =
    entity(as[Json]) { json =>
      decoder.decodeJson(json) match {
        case Right(value) => f(value)
        case Left(OAuthExpirationDecodingFailure()) =>
          complete(error(StatusCodes.BadRequest, s"$ProblemPrefix:invalid-expiration", "OAuth client expiration is invalid"))
        case Left(_) => complete(error(StatusCodes.BadRequest, s"$ProblemPrefix:invalid-policy", "OAuth client policy is invalid"))
      }
    }

  private def correlationId(implicit tracingContext: TracingContext): Option[OAuthAuditCorrelationId] =
    Option(tracingContext.correlationId).map(OAuthAuditCorrelationId.apply)

  private def managementResponse[A: Encoder](
    successStatus: org.apache.pekko.http.scaladsl.model.StatusCode
  )(
    result: OAuthClientManagementResult[A]
  )(implicit tracingContext: TracingContext
  ): ToResponseMarshallable =
    result match {
      case Success(value)        => successStatus -> Encoder[A].apply(value)
      case OrganizationNotFound  => error(StatusCodes.NotFound, s"$ProblemPrefix:organization-not-found", "Organization not found")
      case ClientNotFound        => error(StatusCodes.NotFound, s"$ProblemPrefix:client-not-found", "OAuth client not found")
      case ProjectNotFound       => error(StatusCodes.NotFound, s"$ProblemPrefix:project-not-found", "Project not found")
      case OperationNotPermitted => error(StatusCodes.Forbidden, s"$ProblemPrefix:operation-not-permitted", "OAuth client operation not permitted")
      case InvalidPolicy         => error(StatusCodes.BadRequest, s"$ProblemPrefix:invalid-policy", "OAuth client policy is invalid")
      case InvalidExpiration     => error(StatusCodes.BadRequest, s"$ProblemPrefix:invalid-expiration", "OAuth client expiration is invalid")
      case NameConflict          => error(StatusCodes.Conflict, s"$ProblemPrefix:name-conflict", "OAuth client name already exists")
      case StaleVersion(current) =>
        error(StatusCodes.Conflict, s"$ProblemPrefix:stale-version", "OAuth client version is stale", extension = Map("currentVersion" -> current))
      case NotActive              => error(StatusCodes.Conflict, s"$ProblemPrefix:not-active", "OAuth client is not active")
      case PersistenceUnavailable => error(StatusCodes.ServiceUnavailable, "error:service-unavailable", "Service unavailable")
    }
}

object OAuthClientRouter {
  val OrganizationsPath = "organizations"
  val OAuthClientsPath  = "oauth-clients"
  val RotateSecretPath  = "rotate-secret"
  val RevokePath        = "revoke"
  val AuditEventsPath   = "audit-events"
  val ProblemPrefix     = "oauth-client"
}
