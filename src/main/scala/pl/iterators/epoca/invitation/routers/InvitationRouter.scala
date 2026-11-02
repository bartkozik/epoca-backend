package pl.iterators.epoca.invitation.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.invitation.services.InvitationAcceptService.{InvitationAcceptRequest, InvitationAcceptResult}
import pl.iterators.epoca.invitation.services.InvitationListService.InvitationListResult
import pl.iterators.epoca.invitation.services.OrganizationInvitationCreateService.{
  OrganizationInvitationCreateRequest,
  OrganizationInvitationCreateResult
}
import pl.iterators.epoca.invitation.services.OrganizationInvitationDeleteService.OrganizationInvitationDeleteResult
import pl.iterators.epoca.invitation.services.OrganizationInvitationListService.OrganizationInvitationListResult
import pl.iterators.epoca.invitation.services._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class InvitationRouter(
  invitationAcceptService: InvitationAcceptService,
  invitationListService: InvitationListService,
  organizationInvitationCreateService: OrganizationInvitationCreateService,
  organizationInvitationListService: OrganizationInvitationListService,
  organizationInvitationDeleteService: OrganizationInvitationDeleteService
)(implicit runtime: IORuntime)
    extends BaseRouter {

  import InvitationRouter._
  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(invitationsPath) {
      (get & pathEndOrSingleSlash) {
        complete(invitationListService.list(auth)) { case InvitationListResult.Ok(invitations) =>
          StatusCodes.OK -> invitations
        }
      } ~ (post & pathEndOrSingleSlash & entity(as[InvitationAcceptRequest])) { request =>
        complete(invitationAcceptService.accept(auth, request)) {
          case InvitationAcceptResult.Done => StatusCodes.OK
          case r @ InvitationAcceptResult.SignInRequired =>
            error(StatusCodes.Unauthorized, typeFromResult[r.type], "Sign in again to accept the invitation")
          case r @ InvitationAcceptResult.AlreadyMember      => error(StatusCodes.Conflict, typeFromResult[r.type], "Already a member")
          case r @ InvitationAcceptResult.InvitationNotFound => error(StatusCodes.NotFound, typeFromResult[r.type], "Invitation not found")
        }
      }
    } ~ pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / invitationsPath) { slug =>
      parameter("version".?) {
        case Some("2") => reject
        case Some(_)   => complete(error(StatusCodes.BadRequest, "invitation:invalid-version", "Unsupported invitation API version"))
        case None =>
          (post & pathEndOrSingleSlash & entity(as[OrganizationInvitationCreateRequest])) { request =>
            complete(organizationInvitationCreateService.create(auth, slug, request)) {
              case OrganizationInvitationCreateResult.Created(organizationInvitation) => StatusCodes.Created -> organizationInvitation
              case r @ OrganizationInvitationCreateResult.InvitationAlreadyExists =>
                error(StatusCodes.Conflict, typeFromResult[r.type], "Invitation already exists")
              case r @ OrganizationInvitationCreateResult.IdentityConflict => error(StatusCodes.Conflict, typeFromResult[r.type], "Identity conflict")
              case r @ OrganizationInvitationCreateResult.InvalidRequest   => error(StatusCodes.BadRequest, typeFromResult[r.type], "Invalid request")
              case r @ OrganizationInvitationCreateResult.AlreadyMember =>
                error(StatusCodes.Conflict, typeFromResult[r.type], "User is already a member")
              case r @ OrganizationInvitationCreateResult.OperationNotPermitted =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted")
              case r @ OrganizationInvitationCreateResult.OrganizationNotFound =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
            }
          } ~ (get & pathEndOrSingleSlash & pagination & parameters("id".as[InvitationId].?)) { (pagination, id) =>
            val filters = InvitationFilters(id = id, pageSizeAndNumber = pagination)
            complete(organizationInvitationListService.list(auth, slug, filters)) {
              case OrganizationInvitationListResult.Ok(organizationInvitationListDtos) => StatusCodes.OK -> organizationInvitationListDtos
              case r @ OrganizationInvitationListResult.OperationNotPermitted =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to invitations"))
              case r @ OrganizationInvitationListResult.OrganizationNotFound =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
            }
          } ~ pathPrefix(JavaUUID.as[InvitationId]) { id =>
            (delete & pathEndOrSingleSlash) {
              complete(organizationInvitationDeleteService.delete(auth, slug, id)) {
                case r @ OrganizationInvitationDeleteResult.InvitationNotPending =>
                  error(StatusCodes.Conflict, typeFromResult[r.type], "Invitation is not pending")
                case OrganizationInvitationDeleteResult.Deleted => StatusCodes.NoContent
                case r @ OrganizationInvitationDeleteResult.OperationNotPermitted =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to invitations"))
                case r @ OrganizationInvitationDeleteResult.InvitationNotFound =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Invitation not found")
                case r @ OrganizationInvitationDeleteResult.OrganizationNotFound =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
              }
            }
          }
      }
    }
}

object InvitationRouter {
  val organizationsPath = "organizations"
  val invitationsPath   = "invitations"
}
