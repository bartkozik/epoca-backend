package pl.iterators.epoca.organization.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.{OrganizationFilters, OrganizationSortBy}
import pl.iterators.epoca.organization.services.OrganizationCreateService.{OrganizationCreateRequest, OrganizationCreateResult}
import pl.iterators.epoca.organization.services.OrganizationDeleteService.OrganizationDeleteResult
import pl.iterators.epoca.organization.services.OrganizationFindService.OrganizationFindResult
import pl.iterators.epoca.organization.services.OrganizationListService.OrganizationListResult
import pl.iterators.epoca.organization.services.OrganizationUpdateService._
import pl.iterators.epoca.organization.services.OrganizationUserAssignService.{OrganizationUserAssignRequest, OrganizationUserAssignResult}
import pl.iterators.epoca.organization.services.OrganizationUserDeassignService.{OrganizationDeassignRequest, OrganizationDeassignResult}
import pl.iterators.epoca.organization.services._
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class OrganizationRouter(
  organizationCreateService: OrganizationCreateService,
  organizationListService: OrganizationListService,
  organizationUpdateService: OrganizationUpdateService,
  organizationDeleteService: OrganizationDeleteService,
  organizationFindService: OrganizationFindService,
  organizationUserAssignService: OrganizationUserAssignService,
  organizationUserDeassignService: OrganizationUserDeassignService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import OrganizationRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath) {
      (pathEndOrSingleSlash & post & entity(as[OrganizationCreateRequest])) { request =>
        complete(organizationCreateService.create(auth, request)) {
          case OrganizationCreateResult.Created(organization) => StatusCodes.Created -> organization
          case r @ OrganizationCreateResult.UserOrganizationsLimitExceeded(limit, actualCount) =>
            error(
              StatusCodes.Conflict,
              typeFromResult[r.type],
              "Organization limit for user exceeded",
              Some(s"Limit: $limit, actual count: $actualCount"),
              Map("limit" -> limit, "actualCount" -> actualCount)
            )
          case r @ OrganizationCreateResult.SlugIsAlreadyTaken => error(StatusCodes.Conflict, typeFromResult[r.type], "Slug is already taken")
          case r @ OrganizationCreateResult.OperationNotPermitted =>
            error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted")
        }
      } ~
        (pathEndOrSingleSlash & get & parameters("id".as[OrganizationId].?, "sortBy".as[OrganizationSortBy].?) & pagination) {
          (
            id,
            sortBy,
            pagination
          ) =>
            val filters =
              OrganizationFilters(id = id, sortBy = sortBy, pageSizeAndNumber = pagination)
            complete(organizationListService.list(auth, filters)) { case OrganizationListResult.Ok(organizations) =>
              StatusCodes.OK -> organizations
            }
        } ~ pathPrefix(Segment.as[OrganizationSlug]) { slug =>
          (pathEndOrSingleSlash & get) {
            complete(organizationFindService.find(auth, slug)) {
              case OrganizationFindResult.Ok(organization) => StatusCodes.OK -> organization
              case OrganizationFindResult.NotFound         => StatusCodes.NotFound
            }
          } ~ (pathEndOrSingleSlash & patch & entity(as[OrganizationUpdateRequest])) { request =>
            complete(organizationUpdateService.update(auth, slug, request)) {
              case OrganizationUpdateResult.Updated(organization)    => StatusCodes.OK -> organization
              case r @ OrganizationUpdateResult.OrganizationNotFound => error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
              case r @ OrganizationUpdateResult.OperationNotPermitted =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to invitations"))
            }
          } ~ (pathEndOrSingleSlash & delete) {
            complete(organizationDeleteService.delete(auth, slug)) {
              case OrganizationDeleteResult.Deleted                  => StatusCodes.NoContent
              case r @ OrganizationDeleteResult.OrganizationNotFound => error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
              case r @ OrganizationDeleteResult.OperationNotPermitted =>
                error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User cannot delete organization"))
              case r @ OrganizationDeleteResult.OrganizationHasUsers =>
                error(StatusCodes.BadRequest, typeFromResult[r.type], "Organization has users")
            }
          } ~ pathPrefix(usersPath) {
            (path(assignPath) & pathEndOrSingleSlash & post & entity(as[OrganizationUserAssignRequest])) { request =>
              complete(organizationUserAssignService.assign(auth, slug, request)) {
                case OrganizationUserAssignResult.Assigned => StatusCodes.OK
                case r @ OrganizationUserAssignResult.OrganizationNotFound =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
                case r @ OrganizationUserAssignResult.UserIsNotOrganizationMember =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "User not found in organization", Some(s"User not found: ${request.userId}"))
                case r @ OrganizationUserAssignResult.InvalidRequest => error(StatusCodes.BadRequest, typeFromResult[r.type], "Invalid request")
                case r @ OrganizationUserAssignResult.OperationNotPermitted =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User is not admin"))
                case r @ OrganizationUserAssignResult.UserAlreadyAssigned =>
                  error(StatusCodes.Conflict, typeFromResult[r.type], "User already assigned")
                case r @ OrganizationUserAssignResult.EmptyRoles =>
                  error(StatusCodes.BadRequest, typeFromResult[r.type], "Roles should not be empty")

              }
            } ~ (path(deassignPath) & pathEndOrSingleSlash & post & entity(as[OrganizationDeassignRequest])) { request =>
              complete(organizationUserDeassignService.deassign(auth, slug, request)) {
                case OrganizationDeassignResult.Deassigned         => StatusCodes.NoContent
                case r @ OrganizationDeassignResult.InvalidRequest => error(StatusCodes.BadRequest, typeFromResult[r.type], "Invalid request")
                case r @ OrganizationDeassignResult.OrganizationNotFound =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Organization not found")
                case r @ OrganizationDeassignResult.OperationNotPermitted =>
                  error(StatusCodes.NotFound, typeFromResult[r.type], "Operation not permitted", Some("User is not admin"))
                case r @ OrganizationDeassignResult.UserAlreadyUnassigned =>
                  error(StatusCodes.BadRequest, typeFromResult[r.type], "User already unassigned")
                case r @ OrganizationDeassignResult.OwnerNotDeassignable =>
                  error(StatusCodes.Conflict, typeFromResult[r.type], "Organization creator cannot be deassigned")
              }
            }
          }
        }
    }
}

object OrganizationRouter {
  val organizationsPath = "organizations"
  val usersPath         = "users"
  val assignPath        = "assign"
  val deassignPath      = "deassign"
}
