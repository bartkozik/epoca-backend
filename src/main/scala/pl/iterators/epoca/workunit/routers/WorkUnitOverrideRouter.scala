package pl.iterators.epoca.workunit.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.services.{
  ReplaceWorkUnitOverrideRequest,
  UndoWorkUnitOverrideRequest,
  WorkUnitOverrideResult,
  WorkUnitOverrideService
}

class WorkUnitOverrideRouter(service: WorkUnitOverrideService)(implicit runtime: IORuntime) extends BaseRouter {
  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix("organizations" / Segment.as[OrganizationSlug] / "work-units" / JavaUUID.as[WorkUnitId]) { (slug, id) =>
      path("override") {
        get {
          complete(service.get(auth, slug, id))(response)
        } ~ (put & entity(as[ReplaceWorkUnitOverrideRequest])) { request =>
          complete(service.replace(auth, slug, id, request))(response)
        } ~ (delete & parameter("expectedRevision".as[Long])) { revision =>
          complete(service.delete(auth, slug, id, revision))(response)
        }
      } ~ (post & path("override" / "undo") & entity(as[UndoWorkUnitOverrideRequest])) { request =>
        complete(service.undo(auth, slug, id, request))(response)
      } ~ (get & path("override-history") & parameters("upToVersion".as[Long], "pageSize".as[Int].?, "pageNumber".as[Int].?)) {
        (
          version,
          size,
          number
        ) =>
          complete(service.history(auth, slug, id, version, size, number)) {
            case Right(page) => StatusCodes.OK -> page
            case Left(error) => response(error)
          }
      } ~ pathPrefix("pm-classification") {
        (pathEndOrSingleSlash | path("history")) {
          complete(error(StatusCodes.Gone, "work-unit-override:refresh-required", "Refresh this work unit and use the override API"))
        }
      }
    }

  private def response(result: WorkUnitOverrideResult)(implicit tracingContext: TracingContext): ToResponseMarshallable = {
    import WorkUnitOverrideResult._
    result match {
      case Ok(dto) => StatusCodes.OK -> dto
      case StaleRevision(revision) =>
        error(
          StatusCodes.Conflict,
          "work-unit-override:stale-revision",
          "Work unit revision is stale",
          extension = Map("currentRevision" -> revision)
        )
      case InvalidRequest(field) =>
        error(StatusCodes.BadRequest, "work-unit-override:invalid-request", "Invalid override request", extension = Map("field" -> field))
      case StaleUndoTarget(revision) =>
        error(
          StatusCodes.Conflict,
          "work-unit-override:stale-undo-target",
          "Correction is no longer the undo target",
          extension = Map("currentRevision" -> revision)
        )
      case Forbidden        => error(StatusCodes.Forbidden, "work-unit-override:forbidden", "Override access forbidden")
      case WorkUnitNotFound => error(StatusCodes.NotFound, "work-unit-override:work-unit-not-found", "Work unit not found")
      case ProjectNotFound =>
        error(StatusCodes.NotFound, "work-unit-override:project-not-found", "Project not found", extension = Map("field" -> "projectId"))
      case ProjectUnavailable =>
        error(StatusCodes.BadRequest, "work-unit-override:project-unavailable", "Project is unavailable", extension = Map("field" -> "projectId"))
    }
  }
}
