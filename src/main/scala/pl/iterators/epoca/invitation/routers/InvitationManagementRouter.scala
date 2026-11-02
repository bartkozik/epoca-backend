package pl.iterators.epoca.invitation.routers

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.services.{InvitationDeliveryService, InvitationManagementJson, InvitationManagementService}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

import java.util.UUID
import scala.util.Try

class InvitationManagementRouter(
  management: InvitationManagementService,
  delivery: InvitationDeliveryService,
  enabled: Boolean
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import InvitationManagementService._
  private def result(command: IO[Result], success: StatusCode)(implicit tracing: TracingContext): Route = complete(command) {
    case Ok(dto, _) =>
      if (success == StatusCodes.NoContent) HttpResponse(success)
      else HttpResponse(success, entity = HttpEntity(ContentTypes.`application/json`, dto.noSpaces))
    case NotFound          => error(StatusCodes.NotFound, "invitation:not-found", "Invitation not found")
    case _: InvalidRequest => error(StatusCodes.BadRequest, "invitation:invalid-request", "Invalid invitation request")
    case RateLimited(retry) =>
      HttpResponse(
        StatusCodes.TooManyRequests,
        List(RawHeader("Retry-After", retry.toString)),
        HttpEntity(ContentTypes.`application/json`, "{\"type\":\"result:invitation:rate-limited\"}")
      )
    case value =>
      error(
        StatusCodes.Conflict,
        "invitation:" + value.getClass.getSimpleName.stripSuffix("$").replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase,
        "Invitation conflict"
      )
  }
  private def idempotent(run: UUID => Route)(implicit tracing: TracingContext): Route = extractRequest { request =>
    val values = request.headers.filter(_.is("idempotency-key"))
    values match {
      case Seq(value) =>
        Try(UUID.fromString(value.value())).toOption.filter(_.toString.equalsIgnoreCase(value.value())) match {
          case Some(id) => run(id)
          case None     => complete(error(StatusCodes.BadRequest, "invitation:invalid-request", "Idempotency-Key must be a UUID"))
        }
      case _ => complete(error(StatusCodes.BadRequest, "invitation:invalid-request", "Idempotency-Key must be a UUID"))
    }
  }
  private def active(auth: AuthContext, slug: OrganizationSlug)(run: => Route)(implicit tracing: TracingContext): Route =
    if (enabled) run
    else
      onSuccess(management.authorize(auth.id, slug).unsafeToFuture()) { owner =>
        if (owner) complete(error(StatusCodes.ServiceUnavailable, "invitation:feature-disabled", "Invitation onboarding is disabled"))
        else complete(error(StatusCodes.NotFound, "invitation:not-found", "Invitation not found"))
      }
  def authRoutes(auth: AuthContext)(implicit tracing: TracingContext): Route =
    pathPrefix("organizations" / Segment.as[OrganizationSlug] / "invitations") { slug =>
      parameter("version".?) { version =>
        if (version.exists(_ != "2")) complete(error(StatusCodes.BadRequest, "invitation:invalid-version", "Unsupported invitation API version"))
        else {
          val collection =
            if (!version.contains("2")) reject
            else
              (post & pathEndOrSingleSlash) {
                active(auth, slug) {
                  idempotent { key =>
                    entity(as[Json])(body => result(delivery.afterCommit(management.create(auth.id, slug, key, body)), StatusCodes.Created))
                  }
                }
              } ~ (get & pathEndOrSingleSlash & pagination & parameter("id".as[InvitationId].?)) { (page, id) =>
                val requested = page.getOrElse(PageSizeAndNumber(50, 1))
                if (requested.size > 200 || requested.number.toLong * requested.size > Int.MaxValue) complete(StatusCodes.BadRequest)
                else result(management.list(auth.id, slug, requested, id, enabled), StatusCodes.OK)
              }
          collection ~ pathPrefix(JavaUUID.as[InvitationId]) { id =>
            (get & pathEndOrSingleSlash) {
              if (version.contains("2")) result(management.detail(auth.id, slug, id), StatusCodes.OK) else reject
            } ~ (patch & pathEndOrSingleSlash) {
              active(auth, slug)(entity(as[Json])(body => result(management.patch(auth.id, slug, id, body), StatusCodes.OK)))
            } ~ (post & path("resend")) {
              active(auth, slug) {
                idempotent { key =>
                  entity(as[Json])(body => result(delivery.afterCommit(management.resend(auth.id, slug, id, key, body)), StatusCodes.OK))
                }
              }
            } ~ (delete & pathEndOrSingleSlash & parameter("expectedRevision".?)) {
              case None if version.isEmpty => reject
              case revision =>
                val body = Json.obj("expectedRevision" -> revision.fold[Json](Json.Null)(Json.fromString))
                InvitationManagementJson.revision(body) match {
                  case Right(value) => result(management.cancel(auth.id, slug, id, value), StatusCodes.NoContent)
                  case Left(_)      => complete(error(StatusCodes.BadRequest, "invitation:invalid-request", "Invalid expectedRevision"))
                }
            }
          }
        }
      }
    }
}
