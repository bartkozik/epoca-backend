package pl.iterators.epoca.`export`.routers

import cats.effect.unsafe.IORuntime
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{MalformedRequestContentRejection, Route}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.export.domain.SpreadsheetDomain.SpreadsheetFormat
import pl.iterators.epoca.export.domain.WorkUnitPivotExport
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService.ExportResult
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.export.services.{ExportWorkUnitPivotTableService, ExportWorkUnitService}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.GlobalFilters
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class ExportRouter(
  exportWorkUnitService: ExportWorkUnitService,
  exportWorkUnitPivotTableService: ExportWorkUnitPivotTableService,
  config: ExportRouter.Config,
  pivotExportAdmissionController: PivotExportAdmissionController
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import ExportRouter._
  import ExportWorkUnitRequest.decoder

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug]) { organizationSlug =>
      (pathPrefix(workUnitPath / exportPath) & post & pathEndOrSingleSlash & entity(as[ExportWorkUnitRequest]) & parameters(
        "format".as[SpreadsheetFormat]
      )) { (request, format) =>
        complete(exportWorkUnitService.`export`(auth, organizationSlug, request, format)) {
          case ExportWorkUnitResult.Ok(fileName, stream, format) => toSpreadsheetResponse(fileName, stream, format)
          case ExportWorkUnitResult.OrganizationNotFound         => StatusCodes.NotFound
          case ExportWorkUnitResult.NoAccessToOrganization       => StatusCodes.NotFound
          case ExportWorkUnitResult.OverrideForbidden            => StatusCodes.Forbidden
        }
      } ~
        (pathPrefix(pivotPath / workUnitPath / exportPath) & post & pathEndOrSingleSlash) {
          pivotExportAdmissionController.withAdmission { admission =>
            (withSizeLimit(config.requestMaxBytes) & entity(as[Json]) & parameters(
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
                  complete(exportWorkUnitPivotTableService.`export`(auth, organizationSlug, rawRequest, filters, format)) {
                    case ExportResult.Ok(artifact) =>
                      pivotExportAdmissionController.mapArtifact(artifact, admission)(toPivotArtifactResponse(artifact))
                    case ExportResult.NoContent                   => StatusCodes.NoContent
                    case ExportResult.InvalidConfiguration(error) => StatusCodes.BadRequest           -> error
                    case ExportResult.InvalidExportRequest(error) => StatusCodes.BadRequest           -> error
                    case ExportResult.TooLarge(error)             => StatusCodes.UnprocessableContent -> error
                    case ExportResult.OrganizationNotFound        => StatusCodes.NotFound
                    case ExportResult.NoAccessToOrganization      => StatusCodes.NotFound
                    case ExportResult.InsufficientRole =>
                      error(StatusCodes.Forbidden, "work-unit-pivot-query-service:result", "User's role doesn't allow to access pivot table")
                    case ExportResult.MalformedRequest => StatusCodes.BadRequest
                  }
                }
            }
          } {
            org.apache.pekko.http.scaladsl.server.Directives
              .complete(error(StatusCodes.ServiceUnavailable, "pivot-export-overloaded", "Pivot export capacity is exhausted"))
          }
        }
    }
}

object ExportRouter {
  final case class Config(requestMaxBytes: Long, maxConcurrentRequests: Int) {
    require(
      requestMaxBytes == WorkUnitPivotExport.Limits.requestEntityMaxBytes,
      s"export.pivot.requestMaxBytes must equal ${WorkUnitPivotExport.Limits.requestEntityMaxBytes}"
    )
    require(maxConcurrentRequests > 0, "export.pivot.maxConcurrentRequests must be positive")
    require(
      maxConcurrentRequests <= Config.MaximumConcurrentRequests,
      s"export.pivot.maxConcurrentRequests must not exceed ${Config.MaximumConcurrentRequests}"
    )
  }

  object Config {
    final val MaximumConcurrentRequests = 2
  }

  private final val organizationsPath = "organizations"
  private final val workUnitPath      = "work-units"
  private final val exportPath        = "export"
  private final val pivotPath         = "pivot-tables"
}

final class PivotExportAdmissionController(maxConcurrentRequests: Int) {
  require(maxConcurrentRequests > 0, "pivot export maxConcurrentRequests must be positive")

  private val semaphore = new Semaphore(maxConcurrentRequests)

  def withAdmission(inner: PivotExportAdmission => Route)(exhausted: => Route): Route = { context =>
    if (!semaphore.tryAcquire()) exhausted(context)
    else {
      val admission = new PivotExportAdmission(semaphore)
      val result =
        try inner(admission)(context)
        catch {
          case NonFatal(error) =>
            admission.releaseIfOwned()
            throw error
        }
      result.andThen { case _ => admission.releaseIfOwned() }(ExecutionContext.parasitic)
    }
  }

  def mapArtifact(
    artifact: pl.iterators.epoca.`export`.domain.PivotExportArtifact,
    admission: PivotExportAdmission
  )(
    response: => org.apache.pekko.http.scaladsl.model.HttpResponse
  )(implicit runtime: IORuntime
  ): org.apache.pekko.http.scaladsl.model.HttpResponse =
    try {
      admission.transferTo(artifact)
      response
    } catch {
      case NonFatal(error) =>
        try {
          val _ = artifact.discard.unsafeRunSync()
        } catch {
          case NonFatal(cleanupError) => error.addSuppressed(cleanupError)
        }
        throw error
    }
}

final class PivotExportAdmission private[routers] (semaphore: Semaphore) {
  private val transferred = new AtomicBoolean(false)
  private val released    = new AtomicBoolean(false)

  def transferTo(artifact: pl.iterators.epoca.`export`.domain.PivotExportArtifact): Unit = {
    require(transferred.compareAndSet(false, true), "pivot export admission is already transferred")
    try artifact.releaseAdmissionOnTerminal(() => release())
    catch {
      case error: Throwable =>
        transferred.set(false)
        throw error
    }
  }

  def releaseIfOwned(): Unit = if (!transferred.get()) release()

  private def release(): Unit = if (released.compareAndSet(false, true)) semaphore.release()
}
