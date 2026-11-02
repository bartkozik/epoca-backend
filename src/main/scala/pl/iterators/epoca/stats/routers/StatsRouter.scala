package pl.iterators.epoca.stats.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters

class StatsRouter(
  workUnitPivotTableService: WorkUnitPivotTableService,
  workUnitPivotQueryService: WorkUnitPivotQueryService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import StatsRouter._

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / pivotPath / workUnitPath) { organizationSlug =>
      pathPrefix(queryPath) {
        (post & pathEndOrSingleSlash & entity(as[Json]) & parameters(
          "dateFrom".as[WorkUnitDate],
          "dateTo".as[WorkUnitDate].?,
          "projectId".as[ProjectId].*,
          "userId".as[UserId].*,
          "clientId".as[ClientId].*
        )) {
          (
            rawRequest,
            dateFrom,
            dateTo,
            projectIds,
            userIds,
            clientIds
          ) =>
            val filters = GlobalFilters(dateFrom, dateTo, projectIds.toSeq, userIds.toSeq, clientIds.toSeq)
            complete(workUnitPivotQueryService.query(auth, organizationSlug, rawRequest, filters)) {
              case Result.Ok(response)                => StatusCodes.OK                   -> response
              case Result.InvalidConfiguration(error) => StatusCodes.BadRequest           -> error
              case Result.TooLarge(error)             => StatusCodes.UnprocessableContent -> error
              case Result.OrganizationNotFound        => StatusCodes.NotFound
              case Result.NoAccessToOrganization      => StatusCodes.NotFound
              case r @ Result.InsufficientRole =>
                error(StatusCodes.Forbidden, typeFromResult[r.type], "User's role doesn't allow to access pivot table")
            }
        }
      } ~
        (post & pathEndOrSingleSlash & entity(as[WorkUnitPivotTableRequest]) & parameters(
          "dateFrom".as[WorkUnitDate].?,
          "dateTo".as[WorkUnitDate].?,
          "projectId".as[ProjectId].*,
          "userId".as[UserId].*,
          "clientId".as[ClientId].*
        )) {
          (
            request,
            dateFrom,
            dateTo,
            projectIds,
            userIds,
            clientIds
          ) =>
            val filters = WorkUnitFilters(
              dateFrom = dateFrom,
              dateTo = dateTo,
              projectIds = Option.when(projectIds.nonEmpty)(projectIds.toSeq),
              userIds = Option.when(userIds.nonEmpty)(userIds.toSeq),
              clientIds = Option.when(clientIds.nonEmpty)(clientIds.toSeq)
            )
            complete(workUnitPivotTableService.buildPivotTable(auth, organizationSlug, request, filters)) {
              case WorkUnitPivotTableResult.Ok(data)               => StatusCodes.OK         -> data
              case WorkUnitPivotTableResult.OrganizationNotFound   => StatusCodes.NotFound
              case WorkUnitPivotTableResult.NoAccessToOrganization => StatusCodes.NotFound
              case WorkUnitPivotTableResult.InsufficientRole       => StatusCodes.Forbidden  -> "User's role doesn't allow to access pivot table"
              case WorkUnitPivotTableResult.KeysRequired           => StatusCodes.BadRequest -> "Provide at least one key in request"
              case WorkUnitPivotTableResult.ValuesRequired         => StatusCodes.BadRequest -> "Provide at least one value in request"
            }
        }
    }
}

object StatsRouter {
  private final val organizationsPath = "organizations"
  private final val workUnitPath      = "work-units"
  private final val pivotPath         = "pivot-tables"
  private final val queryPath         = "query"
}
