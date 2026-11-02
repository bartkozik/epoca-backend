package pl.iterators.epoca.oauth.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{MalformedHeaderRejection, MalformedRequestContentRejection, Route}
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientType}
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.export.domain.SpreadsheetDomain.SpreadsheetFormat
import pl.iterators.epoca.export.domain.WorkUnitPivotExport
import pl.iterators.epoca.export.routers.{ExportRouter, PivotExportAdmissionController}
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.adapters.OAuthWorkUnitWriteAdapter.OAuthWorkUnitCreateRequest
import pl.iterators.epoca.oauth.adapters.{
  OAuthAccessPolicy,
  OAuthClientReader,
  OAuthExporter,
  OAuthOrganizationReader,
  OAuthProjectReader,
  OAuthReportReader,
  OAuthUserReader,
  OAuthWorkUnitReader,
  OAuthWorkUnitWriter
}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.{OrganizationFilters, OrganizationSortBy}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.GlobalFilters
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitId, WorkUnitState}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest

import java.util.UUID
import scala.util.Try

final class OAuthApiRouter(
  organizationReadAdapter: OAuthOrganizationReader,
  userReadAdapter: OAuthUserReader,
  clientReadAdapter: OAuthClientReader,
  projectReadAdapter: OAuthProjectReader,
  workUnitReadAdapter: OAuthWorkUnitReader,
  workUnitWriteAdapter: OAuthWorkUnitWriter,
  reportAdapter: OAuthReportReader,
  exportAdapter: OAuthExporter,
  pivotExportAdmissionController: PivotExportAdmissionController = new PivotExportAdmissionController(ExportRouter.Config.MaximumConcurrentRequests)
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import OAuthApiRouter._
  import ExportWorkUnitRequest.decoder

  def routes(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route =
    (get & path(OrganizationsPath) & parameters("id".as[OrganizationId].?, "sortBy".as[OrganizationSortBy].?) & pagination) {
      (
        id,
        sortBy,
        page
      ) =>
        complete(organizationReadAdapter.list(OAuthAccessPolicy.from(auth), OrganizationFilters(id = id, sortBy = sortBy, pageSizeAndNumber = page)))(
          value => StatusCodes.OK -> value
        )
    } ~ pathPrefix(OrganizationsPath / Segment.as[OrganizationSlug]) { organizationSlug =>
      (get & pathEndOrSingleSlash) {
        complete(organizationReadAdapter.find(OAuthAccessPolicy.from(auth), organizationSlug)) {
          case Some(value) => StatusCodes.OK -> value
          case None        => StatusCodes.NotFound
        }
      } ~ pathPrefix(UsersPath) {
        (get & path(JavaUUID.as[UserId] / DetailsPath)) { userId =>
          complete(userReadAdapter.details(OAuthAccessPolicy.from(auth), organizationSlug, userId)) {
            case Ok(value) => StatusCodes.OK -> value
            case NotFound  => StatusCodes.NotFound
          }
        } ~ (get & pathEndOrSingleSlash & parameter("includeArchived".as[Boolean].?(false))) { includeArchived =>
          complete(userReadAdapter.list(OAuthAccessPolicy.from(auth), organizationSlug, includeArchived)) {
            case Ok(value) => StatusCodes.OK -> value
            case NotFound  => StatusCodes.NotFound
          }
        }
      } ~ pathPrefix(ClientsPath) {
        (get & pathEndOrSingleSlash & pagination & parameters("id".as[ClientId].?, "type".as[ClientType].?, "includeArchived".as[Boolean].?(false))) {
          (
            page,
            id,
            clientType,
            includeArchived
          ) =>
            val filters = ClientFilters(id = id, `type` = clientType, pageSizeAndNumber = page, includeArchived = includeArchived)
            complete(clientReadAdapter.list(OAuthAccessPolicy.from(auth), organizationSlug, filters)) {
              case Ok(value) => StatusCodes.OK -> value
              case NotFound  => StatusCodes.NotFound
            }
        }
      } ~ pathPrefix(ProjectsPath) {
        (get & pathEndOrSingleSlash & pagination & parameters(
          "id".as[ProjectId].?,
          "clientId".as[ClientId].*,
          "includeArchived".as[Boolean].?(false)
        )) {
          (
            page,
            id,
            clientIds,
            includeArchived
          ) =>
            val filters = ProjectFilters(
              id = id,
              pageSizeAndNumber = page,
              clientIds = Option.when(clientIds.nonEmpty)(clientIds.toSeq),
              includeArchived = includeArchived
            )
            complete(projectReadAdapter.list(OAuthAccessPolicy.from(auth), organizationSlug, filters)) {
              case Ok(value) => StatusCodes.OK -> value
              case NotFound  => StatusCodes.NotFound
            }
        } ~ (get & path(JavaUUID.as[ProjectId])) { projectId =>
          complete(projectReadAdapter.find(OAuthAccessPolicy.from(auth), organizationSlug, projectId)) {
            case Ok(value) => StatusCodes.OK -> value
            case NotFound  => StatusCodes.NotFound
          }
        }
      } ~ pathPrefix(WorkUnitsPath) {
        (get & pathEndOrSingleSlash & pagination & parameters(
          "id".as[WorkUnitId].?,
          "dateFrom".as[WorkUnitDate].?,
          "dateTo".as[WorkUnitDate].?,
          "projectId".as[ProjectId].*,
          "userId".as[UserId].*,
          "state".as[WorkUnitState].*,
          "clientId".as[ClientId].*,
          "includeArchived".as[Boolean].?(false)
        )) {
          (
            page,
            id,
            dateFrom,
            dateTo,
            projectIds,
            userIds,
            states,
            clientIds,
            includeArchived
          ) =>
            val filters = WorkUnitFilters(
              pageSizeAndNumber = page,
              id = id,
              dateFrom = dateFrom,
              dateTo = dateTo,
              projectIds = Option.when(projectIds.nonEmpty)(projectIds.toSeq),
              userIds = Option.when(userIds.nonEmpty)(userIds.toSeq),
              states = Option.when(states.nonEmpty)(states.toSeq),
              clientIds = Option.when(clientIds.nonEmpty)(clientIds.toSeq),
              includeArchived = includeArchived
            )
            complete(workUnitReadAdapter.list(OAuthAccessPolicy.from(auth), organizationSlug, filters)) {
              case Ok(value) => StatusCodes.OK -> value
              case NotFound  => StatusCodes.NotFound
            }
        } ~ (post & path(ExportPath) & entity(as[ExportWorkUnitRequest]) & parameters("format".as[SpreadsheetFormat])) { (request, format) =>
          complete(exportAdapter.exportWorkUnits(OAuthAccessPolicy.from(auth), organizationSlug, request, format)) {
            case ExportWorkUnitResult.Ok(fileName, stream, resultFormat) => toSpreadsheetResponse(fileName, stream, resultFormat)
            case ExportWorkUnitResult.OrganizationNotFound               => StatusCodes.NotFound
            case ExportWorkUnitResult.NoAccessToOrganization             => StatusCodes.NotFound
            case ExportWorkUnitResult.OverrideForbidden                  => StatusCodes.Forbidden
          }
        } ~ (post & pathEndOrSingleSlash & entity(as[OAuthWorkUnitCreateRequest])) { request =>
          withIdempotencyKey { key =>
            complete(
              workUnitWriteAdapter.create(OAuthAccessPolicy.from(auth), auth.actorId, organizationSlug, request, key, tracingContext.correlationId)
            )(writeResponse)
          }
        } ~ pathPrefix(JavaUUID.as[WorkUnitId]) { workUnitId =>
          (patch & pathEndOrSingleSlash & entity(as[WorkUnitUpdateRequest])) { request =>
            withIdempotencyKey { key =>
              complete(
                workUnitWriteAdapter
                  .update(OAuthAccessPolicy.from(auth), auth.actorId, organizationSlug, workUnitId, request, key, tracingContext.correlationId)
              )(writeResponse)
            }
          }
        }
      } ~ pathPrefix(PivotTablesPath / WorkUnitsPath) {
        (post & path(QueryPath) & entity(as[Json]) & parameters(
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
            complete(reportAdapter.fullPivot(OAuthAccessPolicy.from(auth), organizationSlug, rawRequest, filters)) {
              case Result.Ok(response)                => StatusCodes.OK                   -> response
              case Result.InvalidConfiguration(error) => StatusCodes.BadRequest           -> error
              case Result.TooLarge(error)             => StatusCodes.UnprocessableContent -> error
              case Result.OrganizationNotFound        => StatusCodes.NotFound
              case Result.NoAccessToOrganization      => StatusCodes.NotFound
              case Result.InsufficientRole            => StatusCodes.Forbidden
            }
        } ~ (post & path(ExportPath)) {
          pivotExportAdmissionController.withAdmission { admission =>
            (withSizeLimit(WorkUnitPivotExport.Limits.requestEntityMaxBytes) & entity(as[Json]) & parameters(
              "format".as[SpreadsheetFormat],
              "dateFrom".as[WorkUnitDate],
              "dateTo".as[WorkUnitDate].?,
              "projectId".as[ProjectId].*,
              "userId".as[UserId].*,
              "clientId".as[ClientId].*
            )) {
              (
                rawRequest,
                format,
                dateFrom,
                dateTo,
                projectIds,
                userIds,
                clientIds
              ) =>
                if (!rawRequest.isObject) {
                  val message = "Expected a JSON object at the request root"
                  reject(MalformedRequestContentRejection(message, new IllegalArgumentException(message)))
                } else {
                  val filters = GlobalFilters(dateFrom, dateTo, projectIds.toSeq, userIds.toSeq, clientIds.toSeq)
                  complete(exportAdapter.exportPivot(OAuthAccessPolicy.from(auth), organizationSlug, rawRequest, filters, format)) {
                    case ExportResult.Ok(artifact) =>
                      pivotExportAdmissionController.mapArtifact(artifact, admission)(toPivotArtifactResponse(artifact))
                    case ExportResult.NoContent                   => StatusCodes.NoContent
                    case ExportResult.InvalidConfiguration(error) => StatusCodes.BadRequest           -> error
                    case ExportResult.InvalidExportRequest(error) => StatusCodes.BadRequest           -> error
                    case ExportResult.TooLarge(error)             => StatusCodes.UnprocessableContent -> error
                    case ExportResult.OrganizationNotFound        => StatusCodes.NotFound
                    case ExportResult.NoAccessToOrganization      => StatusCodes.NotFound
                    case ExportResult.InsufficientRole            => StatusCodes.Forbidden
                    case ExportResult.MalformedRequest            => StatusCodes.BadRequest
                  }
                }
            }
          } {
            org.apache.pekko.http.scaladsl.server.Directives
              .complete(error(StatusCodes.ServiceUnavailable, "pivot-export-overloaded", "Pivot export capacity is exhausted"))
          }
        } ~ (post & pathEndOrSingleSlash & entity(as[WorkUnitPivotTableRequest]) & parameters(
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
            complete(reportAdapter.legacyPivot(OAuthAccessPolicy.from(auth), organizationSlug, request, filters)) {
              case WorkUnitPivotTableResult.Ok(rows)               => StatusCodes.OK -> rows
              case WorkUnitPivotTableResult.OrganizationNotFound   => StatusCodes.NotFound
              case WorkUnitPivotTableResult.NoAccessToOrganization => StatusCodes.NotFound
              case WorkUnitPivotTableResult.InsufficientRole       => StatusCodes.Forbidden
              case WorkUnitPivotTableResult.KeysRequired           => StatusCodes.BadRequest
              case WorkUnitPivotTableResult.ValuesRequired         => StatusCodes.BadRequest
            }
        }
      }
    }

  private def withIdempotencyKey(f: Option[WorkUnitIdempotencyKey] => Route): Route =
    optionalHeaderValueByName(IdempotencyKeyHeaderName) {
      case None => f(None)
      case Some(rawKey) =>
        parseIdempotencyKey(rawKey) match {
          case Right(key)    => f(Some(key))
          case Left(message) => reject(MalformedHeaderRejection(IdempotencyKeyHeaderName, message))
        }
    }

  private def writeResponse(result: WorkUnitIdempotencyResult)(implicit tracingContext: TracingContext): ToResponseMarshallable =
    result match {
      case Response(outcome, replayed) =>
        val headers: Seq[HttpHeader] = Option.when(replayed)(RawHeader(IdempotencyReplayedHeaderName, "true")).toSeq
        HttpResponse(
          status = persistedStatus(outcome.status),
          headers = headers,
          entity = HttpEntity(ContentTypes.`application/json`, outcome.body.noSpaces)
        )
      case KeyConflict =>
        error(StatusCodes.Conflict, "work-unit:idempotency-key-conflict", "Idempotency key conflict")
      case VersionUnsupported =>
        error(StatusCodes.Conflict, "work-unit:idempotency-version-unsupported", "Idempotency version unsupported")
      case ResultExpired =>
        error(StatusCodes.Gone, "work-unit:idempotency-result-expired", "Idempotency result expired")
    }

  private def persistedStatus(value: Int): StatusCode =
    StatusCodes.getForKey(value).getOrElse {
      if (value >= 100 && value <= 599) StatusCodes.custom(value, s"Stored status $value")
      else throw new IllegalStateException(s"Stored idempotency response has invalid HTTP status $value")
    }

  private def parseIdempotencyKey(rawValue: String): Either[String, WorkUnitIdempotencyKey] =
    Try(UUID.fromString(rawValue)).toEither
      .flatMap { uuid =>
        Either.cond(uuid.toString.equalsIgnoreCase(rawValue), WorkUnitIdempotencyKey(uuid), "Expected a canonical UUID")
      }
      .left
      .map(_ => "Expected a canonical UUID")
}

object OAuthApiRouter {
  private val OrganizationsPath             = "organizations"
  private val UsersPath                     = "users"
  private val DetailsPath                   = "details"
  private val ClientsPath                   = "clients"
  private val ProjectsPath                  = "projects"
  private val WorkUnitsPath                 = "work-units"
  private val PivotTablesPath               = "pivot-tables"
  private val QueryPath                     = "query"
  private val ExportPath                    = "export"
  private val IdempotencyKeyHeaderName      = "Idempotency-Key"
  private val IdempotencyReplayedHeaderName = "Idempotency-Replayed"
}
