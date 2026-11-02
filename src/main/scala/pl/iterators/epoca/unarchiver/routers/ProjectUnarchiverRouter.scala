package pl.iterators.epoca.unarchiver.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService
import pl.iterators.epoca.unarchiver.services.ProjectUnarchiveService.ProjectUnarchiveResult
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class ProjectUnarchiverRouter(projectUnarchiveService: ProjectUnarchiveService)(implicit runtime: IORuntime) extends BaseRouter {
  import ProjectUnarchiverRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug]) { organizationSlug =>
      pathPrefix(projectsPath / JavaUUID.as[ProjectId] / unarchivePath) { projectId =>
        (post & pathEndOrSingleSlash) {
          complete(projectUnarchiveService.unarchive(auth, organizationSlug, projectId)) {
            case ProjectUnarchiveResult.Unarchived(project)    => StatusCodes.Created  -> project
            case ProjectUnarchiveResult.NoAccessToOrganization => StatusCodes.NotFound
            case ProjectUnarchiveResult.OrganizationNotFound   => StatusCodes.NotFound
            case ProjectUnarchiveResult.ProjectNotFound        => StatusCodes.NotFound
            case ProjectUnarchiveResult.ClientNotFound         => StatusCodes.NotFound
            case ProjectUnarchiveResult.InsufficientRole       => StatusCodes.Forbidden
            case ProjectUnarchiveResult.ProjectNameNotUnique   => StatusCodes.Conflict -> "Project name is already in use"
            case ProjectUnarchiveResult.ClientNameNotUnique    => StatusCodes.Conflict -> "Client name is already in use"
          }
        }
      }
    }
}

object ProjectUnarchiverRouter {
  val organizationsPath = "organizations"
  val projectsPath      = "projects"
  val unarchivePath     = "unarchive"
}
