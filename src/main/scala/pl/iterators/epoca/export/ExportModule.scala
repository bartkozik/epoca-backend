package pl.iterators.epoca.`export`

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.{Config => TypesafeConfig}
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.export.domain.{PivotExportArtifactStore, WorkUnitPivotExport}
import pl.iterators.epoca.export.routers.{ExportRouter, PivotExportAdmissionController}
import pl.iterators.epoca.export.services.{ExportWorkUnitPivotTableService, ExportWorkUnitService, WorkUnitPivotExportRequestValidator}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryRequestValidator, WorkUnitPivotQueryService}
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.services.WorkUnitListService

import java.nio.file.Paths
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

trait ExportModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime

  val config: TypesafeConfig
  val workUnitListService: WorkUnitListService
  val workUnitPivotQueryService: WorkUnitPivotQueryService
  val workUnitPivotQueryRequestValidator: WorkUnitPivotQueryRequestValidator
  val workUnitPivotQueryConfig: WorkUnitPivotQueryService.Config

  lazy val exportWorkUnitService: ExportWorkUnitService = wire[ExportWorkUnitService]
  lazy val pivotExportConfig: ExportModule.PivotConfig =
    ExportModule.PivotConfig(config, workUnitPivotQueryConfig.maxDisplayValueCellCount)
  lazy val workUnitPivotExportRequestValidatorConfig: WorkUnitPivotExportRequestValidator.Config =
    WorkUnitPivotExportRequestValidator.Config(workUnitPivotQueryConfig.maxDisplayValueCellCount)
  lazy val workUnitPivotExportRequestValidator: WorkUnitPivotExportRequestValidator = wire[WorkUnitPivotExportRequestValidator]
  lazy val pivotExportArtifactStoreConfig: PivotExportArtifactStore.Config =
    PivotExportArtifactStore.Config(
      pivotExportConfig.tempDirectory,
      pivotExportConfig.unmaterializedArtifactTimeout,
      pivotExportConfig.streamIdleTimeout
    )
  lazy val pivotExportArtifactStore: PivotExportArtifactStore               = wire[PivotExportArtifactStore]
  lazy val exportWorkUnitPivotTableService: ExportWorkUnitPivotTableService = wire[ExportWorkUnitPivotTableService]

  lazy val exportRouterConfig: ExportRouter.Config =
    ExportRouter.Config(pivotExportConfig.requestMaxBytes, pivotExportConfig.maxConcurrentRequests)
  lazy val pivotExportAdmissionController: PivotExportAdmissionController =
    new PivotExportAdmissionController(exportRouterConfig.maxConcurrentRequests)
  lazy val exportRouter: ExportRouter = wire[ExportRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ exportRouter.authRoutes(auth)

}

object ExportModule {
  final case class PivotConfig(
    tempDirectory: java.nio.file.Path,
    requestMaxBytes: Long,
    maxDisplayValueCellCount: Long,
    unmaterializedArtifactTimeout: FiniteDuration,
    streamIdleTimeout: FiniteDuration,
    maxConcurrentRequests: Int) {
    require(tempDirectory.toString.trim.nonEmpty, "export.pivot.tempDirectory must not be blank")
    require(maxDisplayValueCellCount > 0L, "stats.pivot.maxDisplayValueCellCount must be positive")
    require(
      requestMaxBytes == WorkUnitPivotExport.Limits.requestEntityMaxBytes,
      s"export.pivot.requestMaxBytes must equal ${WorkUnitPivotExport.Limits.requestEntityMaxBytes}"
    )
    require(
      WorkUnitPivotExport.Limits.canonicalBodyBudget(maxDisplayValueCellCount) <= requestMaxBytes,
      s"pivot export canonical body budget ${WorkUnitPivotExport.Limits.canonicalBodyBudget(maxDisplayValueCellCount)} exceeds request limit $requestMaxBytes"
    )
    require(unmaterializedArtifactTimeout.length > 0L, "export.pivot.unmaterializedArtifactTimeout must be positive")
    require(
      unmaterializedArtifactTimeout <= PivotExportArtifactStore.Config.MaximumUnmaterializedTimeout,
      s"export.pivot.unmaterializedArtifactTimeout must not exceed ${PivotExportArtifactStore.Config.MaximumUnmaterializedTimeout}"
    )
    require(streamIdleTimeout.length > 0L, "export.pivot.streamIdleTimeout must be positive")
    require(
      streamIdleTimeout <= PivotExportArtifactStore.Config.MaximumStreamIdleTimeout,
      s"export.pivot.streamIdleTimeout must not exceed ${PivotExportArtifactStore.Config.MaximumStreamIdleTimeout}"
    )
    require(maxConcurrentRequests > 0, "export.pivot.maxConcurrentRequests must be positive")
    require(
      maxConcurrentRequests <= ExportRouter.Config.MaximumConcurrentRequests,
      s"export.pivot.maxConcurrentRequests must not exceed ${ExportRouter.Config.MaximumConcurrentRequests}"
    )
  }

  object PivotConfig {
    def apply(config: TypesafeConfig, maxDisplayValueCellCount: Long): PivotConfig = {
      val tempDirectory = config.getString("export.pivot.tempDirectory")
      PivotConfig(
        Paths.get(tempDirectory),
        config.getLong("export.pivot.requestMaxBytes"),
        maxDisplayValueCellCount,
        Duration.fromNanos(config.getDuration("export.pivot.unmaterializedArtifactTimeout").toNanos),
        Duration.fromNanos(config.getDuration("export.pivot.streamIdleTimeout").toNanos),
        config.getInt("export.pivot.maxConcurrentRequests")
      )
    }
  }
}
