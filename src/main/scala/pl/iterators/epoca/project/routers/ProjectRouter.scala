package pl.iterators.epoca.project.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.services.ProjectAssignService.{ProjectAssignRequest, ProjectAssignResult}
import pl.iterators.epoca.project.services.ProjectBulkAssignService.{ProjectBulkAssignRequest, ProjectBulkAssignResult}
import pl.iterators.epoca.project.services.ProjectBulkDeassignService.{ProjectBulkDeassignRequest, ProjectBulkDeassignResult}
import pl.iterators.epoca.project.services.ProjectCreateService.{ProjectCreateRequest, ProjectCreateResult}
import pl.iterators.epoca.project.services.ProjectDeassignService.ProjectDeassignResult
import pl.iterators.epoca.project.services.ProjectDeleteService.ProjectDeleteResult
import pl.iterators.epoca.project.services.ProjectFindService.ProjectFindResult
import pl.iterators.epoca.project.services.ProjectListService.ProjectListResult
import pl.iterators.epoca.project.services.ProjectUpdateService.{ProjectUpdateRequest, ProjectUpdateResult}
import pl.iterators.epoca.project.services._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class ProjectRouter(
  projectCreateService: ProjectCreateService,
  projectListService: ProjectListService,
  projectFindService: ProjectFindService,
  projectUpdateService: ProjectUpdateService,
  projectDeleteService: ProjectDeleteService,
  projectAssignService: ProjectAssignService,
  projectDeassignService: ProjectDeassignService,
  projectBulkAssignService: ProjectBulkAssignService,
  projectBulkDeassignService: ProjectBulkDeassignService
)(implicit runtime: IORuntime)
    extends BaseRouter {

  import ProjectRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / projectsPath) { organizationSlug =>
      (post & pathEndOrSingleSlash & entity(as[ProjectCreateRequest])) { request =>
        complete(projectCreateService.create(auth, organizationSlug, request)) {
          case ProjectCreateResult.Created(project)                       => StatusCodes.Created  -> project
          case ProjectCreateResult.NoAccessToOrganization                 => StatusCodes.NotFound
          case ProjectCreateResult.ProjectUsersHaveNoAccessToOrganization => StatusCodes.NotFound
          case ProjectCreateResult.OrganizationNotFound                   => StatusCodes.NotFound
          case ProjectCreateResult.ClientNotFound                         => StatusCodes.NotFound
          case ProjectCreateResult.ProjectNameNotUnique                   => StatusCodes.Conflict -> "Name is already used"
        }
      } ~ (get & pathEndOrSingleSlash & pagination & parameters(
        "id".as[ProjectId].?,
        "clientId".as[ClientId].*,
        "includeArchived".as[Boolean].?(false),
        "includeArchivedConnections".as[Boolean].?(false)
      )) {
        (
          pagination,
          id,
          clientId,
          includeArchived,
          includeArchivedConnections
        ) =>
          val clientIds = Option.when(clientId.nonEmpty)(clientId.toSeq)
          val filters   = ProjectFilters(id = id, pageSizeAndNumber = pagination, clientIds = clientIds, includeArchived = includeArchived)
          complete(projectListService.list(auth, organizationSlug, filters, includeArchivedConnections)) {
            case ProjectListResult.Ok(projectListDtos)    => StatusCodes.OK -> projectListDtos
            case ProjectListResult.NoAccessToOrganization => StatusCodes.NotFound
            case ProjectListResult.OrganizationNotFound   => StatusCodes.NotFound
          }
      } ~ pathPrefix(JavaUUID.as[ProjectId]) { id =>
        (get & pathEndOrSingleSlash) {
          complete(projectFindService.find(auth, organizationSlug, id)) {
            case ProjectFindResult.Ok(projectListDtos)    => StatusCodes.OK -> projectListDtos
            case ProjectFindResult.NoAccessToOrganization => StatusCodes.NotFound
            case ProjectFindResult.OrganizationNotFound   => StatusCodes.NotFound
            case ProjectFindResult.ProjectNotFound        => StatusCodes.NotFound
            case ProjectFindResult.NoAccessToProject      => StatusCodes.NotFound
          }
        } ~
          (patch & pathEndOrSingleSlash & entity(as[ProjectUpdateRequest])) { request =>
            complete(projectUpdateService.update(auth, organizationSlug, id, request)) {
              case ProjectUpdateResult.Updated(project)                       => StatusCodes.OK       -> project
              case ProjectUpdateResult.NoAccessToOrganization                 => StatusCodes.NotFound
              case ProjectUpdateResult.ProjectUsersHaveNoAccessToOrganization => StatusCodes.NotFound
              case ProjectUpdateResult.CreatorNotDeassignable                 => StatusCodes.Conflict
              case ProjectUpdateResult.OrganizationNotFound                   => StatusCodes.NotFound
              case ProjectUpdateResult.NotProjectOwner                        => StatusCodes.Forbidden
              case ProjectUpdateResult.ProjectNotFound                        => StatusCodes.NotFound
              case ProjectUpdateResult.ProjectNameNotUnique                   => StatusCodes.Conflict -> "Name is already used"
            }
          } ~ (delete & pathEndOrSingleSlash) {
            complete(projectDeleteService.delete(auth, organizationSlug, id)) {
              case ProjectDeleteResult.Deleted                => StatusCodes.NoContent
              case ProjectDeleteResult.NoAccessToOrganization => StatusCodes.NotFound
              case ProjectDeleteResult.OrganizationNotFound   => StatusCodes.NotFound
              case ProjectDeleteResult.ProjectNotFound        => StatusCodes.NotFound
            }
          } ~ pathPrefix(usersPath) {
            pathPrefix(JavaUUID.as[UserId]) { userId =>
              (post & pathEndOrSingleSlash & entity(as[ProjectAssignRequest])) { request =>
                complete(projectAssignService.assign(auth, organizationSlug, id, userId, request)) {
                  case ProjectAssignResult.Assigned               => StatusCodes.OK
                  case ProjectAssignResult.NoAccessToOrganization => StatusCodes.NotFound
                  case ProjectAssignResult.OrganizationNotFound   => StatusCodes.NotFound
                  case ProjectAssignResult.ProjectNotFound        => StatusCodes.NotFound
                  case ProjectAssignResult.UserNotFound           => StatusCodes.NotFound
                  case ProjectAssignResult.NotAMember             => StatusCodes.Forbidden -> "Target user isn't a member of the organization"
                  case ProjectAssignResult.OperationNotPermitted  => StatusCodes.Forbidden
                  case ProjectAssignResult.UserAlreadyAssigned    => StatusCodes.Conflict
                  case ProjectAssignResult.CreatorNotDeassignable => StatusCodes.Conflict

                }
              } ~ (delete & pathEndOrSingleSlash) {
                complete(projectDeassignService.deassign(auth, organizationSlug, id, userId)) {
                  case ProjectDeassignResult.Deassigned             => StatusCodes.NoContent
                  case ProjectDeassignResult.NoAccessToOrganization => StatusCodes.NotFound
                  case ProjectDeassignResult.OrganizationNotFound   => StatusCodes.NotFound
                  case ProjectDeassignResult.CreatorNotDeassignable => StatusCodes.Conflict
                  case ProjectDeassignResult.ProjectNotFound        => StatusCodes.NotFound
                  case ProjectDeassignResult.UserNotFound           => StatusCodes.NotFound
                  case ProjectDeassignResult.OperationNotPermitted  => StatusCodes.Forbidden
                  case ProjectDeassignResult.UserAlreadyUnassigned  => StatusCodes.BadRequest
                }
              }
            }
          }
      } ~ pathPrefix(allPath / usersPath / JavaUUID.as[UserId]) { userId =>
        (post & pathEndOrSingleSlash & entity(as[ProjectBulkAssignRequest])) { request =>
          complete(projectBulkAssignService.assign(auth, organizationSlug, userId, request)) {
            case ProjectBulkAssignResult.Assigned(data)         => StatusCodes.OK        -> data
            case ProjectBulkAssignResult.OrganizationNotFound   => StatusCodes.NotFound
            case ProjectBulkAssignResult.NoAccessToOrganization => StatusCodes.NotFound
            case ProjectBulkAssignResult.NotAMember             => StatusCodes.Forbidden -> "Target user isn't a member of the organization"
          }
        } ~ (patch & pathEndOrSingleSlash & entity(as[ProjectBulkDeassignRequest])) { request =>
          complete(projectBulkDeassignService.deassign(auth, organizationSlug, userId, request)) {
            case ProjectBulkDeassignResult.Deassigned(data)       => StatusCodes.OK -> data
            case ProjectBulkDeassignResult.OrganizationNotFound   => StatusCodes.NotFound
            case ProjectBulkDeassignResult.NoAccessToOrganization => StatusCodes.NotFound
          }
        }
      }
    }
}

object ProjectRouter {
  val organizationsPath = "organizations"
  val projectsPath      = "projects"
  val usersPath         = "users"
  val allPath           = "all"
}
