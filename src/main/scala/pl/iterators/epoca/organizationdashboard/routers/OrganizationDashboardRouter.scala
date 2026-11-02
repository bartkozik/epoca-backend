package pl.iterators.epoca.organizationdashboard.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.OrganizationUserDetailsResult
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.OrganizationUserListResult
import pl.iterators.epoca.organizationdashboard.services.{OrganizationUserDetailsService, OrganizationUserListService}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class OrganizationDashboardRouter(
  organizationUserDetailsService: OrganizationUserDetailsService,
  organizationUserListService: OrganizationUserListService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import OrganizationDashboardRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / usersPath) { slug =>
      (pathPrefix(JavaUUID.as[UserId] / detailsPath) & pathEndOrSingleSlash & get) { userId =>
        complete(organizationUserDetailsService.details(auth, slug, userId)) {
          case OrganizationUserDetailsResult.Ok(data)             => StatusCodes.OK -> data
          case OrganizationUserDetailsResult.OrganizationNotFound => StatusCodes.NotFound
          case OrganizationUserDetailsResult.OrganizationNoAccess => StatusCodes.NotFound
          case OrganizationUserDetailsResult.NotAMember           => StatusCodes.NotFound
        }
      } ~ (pathEndOrSingleSlash & get & parameters("includeArchived".as[Boolean].?(false), "includeArchivedMembers".as[Boolean].?(false))) {
        (includeArchived, includeArchivedMembers) =>
          complete(organizationUserListService.list(auth, slug, includeArchived, includeArchivedMembers)) {
            case OrganizationUserListResult.OrganizationNotFound  => StatusCodes.NotFound
            case OrganizationUserListResult.OperationNotPermitted => StatusCodes.NotFound
            case OrganizationUserListResult.Ok(orgUsers)          => StatusCodes.OK -> orgUsers
          }
      }
    }
}

object OrganizationDashboardRouter {
  val organizationsPath = "organizations"
  val usersPath         = "users"
  val detailsPath       = "details"
}
