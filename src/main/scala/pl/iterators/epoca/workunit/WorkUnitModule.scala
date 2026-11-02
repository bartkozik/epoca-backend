package pl.iterators.epoca.workunit

import cats.effect.Clock
import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.export.routers.PivotExportAdmissionController
import pl.iterators.epoca.export.services.ExportWorkUnitPivotTableService
import pl.iterators.epoca.oauth.adapters.{
  OAuthClientReadAdapter,
  OAuthExportAdapter,
  OAuthOrganizationReadAdapter,
  OAuthProjectReadAdapter,
  OAuthReportAdapter,
  OAuthUserReadAdapter,
  OAuthWorkUnitReadAdapter,
  OAuthWorkUnitWriteAdapter
}
import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.routers.OAuthApiRouter
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.stats.services.{WorkUnitPivotQueryService, WorkUnitPivotTableService}
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.{DBIOInstances, PostgresIOTransactor}
import pl.iterators.epoca.utils.http.{AuthRouteProvider, OAuthRouteProvider}
import pl.iterators.epoca.utils.job.{SchedulableJob, SchedulableJobConfig, SchedulableJobProvider}
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.jobs.WorkUnitIdempotencyRedactJob
import pl.iterators.epoca.workunit.repositories.{
  WorkUnitIdempotencyRepository,
  WorkUnitIdempotencyRepositoryImpl,
  WorkUnitOrderRepository,
  WorkUnitOrderRepositoryImpl,
  WorkUnitOverrideHistoryRepository,
  WorkUnitOverrideHistoryRepositoryImpl,
  WorkUnitRepository,
  WorkUnitRepositoryImpl
}
import pl.iterators.epoca.workunit.routers.{WorkUnitOverrideRouter, WorkUnitRouter}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services._
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait WorkUnitModule extends AuthRouteProvider with OAuthRouteProvider with SchedulableJobProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository
  val clientRepository: ClientRepository
  val projectRepository: ProjectRepository
  val projectUserRepository: ProjectUserRepository
  val userRepository: UserRepository
  val userIdentityRepository: UserIdentityRepository
  val workUnitPivotTableService: WorkUnitPivotTableService
  val workUnitPivotQueryService: WorkUnitPivotQueryService
  val exportWorkUnitPivotTableService: ExportWorkUnitPivotTableService
  val pivotExportAdmissionController: PivotExportAdmissionController

  lazy val workUnitRepository: WorkUnitRepository                               = wire[WorkUnitRepositoryImpl]
  lazy val workUnitOverrideHistoryRepository: WorkUnitOverrideHistoryRepository = wire[WorkUnitOverrideHistoryRepositoryImpl]
  lazy val workUnitOverrideService: WorkUnitOverrideService                     = wire[WorkUnitOverrideService]
  lazy val workUnitOverrideRouter: WorkUnitOverrideRouter                       = wire[WorkUnitOverrideRouter]
  lazy val workUnitOrderRepository: WorkUnitOrderRepository                     = wire[WorkUnitOrderRepositoryImpl]
  lazy val workUnitIdempotencyRepository: WorkUnitIdempotencyRepository         = wire[WorkUnitIdempotencyRepositoryImpl]

  lazy val workUnitBusinessClock: WorkUnitBusinessClock                             = WorkUnitBusinessClock(config)
  lazy val workUnitDtoService: WorkUnitDtoService                                   = wire[WorkUnitDtoService]
  lazy val workUnitCreateService: WorkUnitCreateService                             = wire[WorkUnitCreateService]
  lazy val workUnitListService: WorkUnitListService                                 = wire[WorkUnitListService]
  lazy val workUnitUpdateService: WorkUnitUpdateService                             = wire[WorkUnitUpdateService]
  lazy val workUnitDeleteService: WorkUnitDeleteService                             = wire[WorkUnitDeleteService]
  lazy val workUnitOrderUpsertService: WorkUnitOrderUpsertService                   = wire[WorkUnitOrderUpsertService]
  lazy val workUnitIdempotencyPreflightService: WorkUnitIdempotencyPreflightService = wire[WorkUnitIdempotencyPreflightService]
  lazy val workUnitWriteOutcomeMapper: WorkUnitWriteOutcomeMapper                   = wire[WorkUnitWriteOutcomeMapper]
  lazy val workUnitIdempotencyConfig: WorkUnitIdempotencyConfig                     = WorkUnitIdempotencyConfig(config)
  implicit lazy val workUnitIdempotencyClock: Clock[DBIO]                           = DBIOInstances.dbioInstance(executor)
  lazy val workUnitIdempotencyService: WorkUnitIdempotencyService                   = wire[WorkUnitIdempotencyService]
  lazy val workUnitIdempotencyRedactService: WorkUnitIdempotencyRedactService       = wire[WorkUnitIdempotencyRedactService]
  lazy val oauthWorkUnitWriteAdapter: OAuthWorkUnitWriteAdapter =
    new OAuthWorkUnitWriteAdapter(
      transactor,
      organizationRepository,
      workUnitCreateService,
      workUnitUpdateService,
      workUnitWriteOutcomeMapper,
      workUnitIdempotencyService
    )
  lazy val oauthOrganizationReadAdapter: OAuthOrganizationReadAdapter =
    new OAuthOrganizationReadAdapter(transactor, organizationRepository)
  lazy val oauthUserReadAdapter: OAuthUserReadAdapter =
    new OAuthUserReadAdapter(
      transactor,
      organizationRepository,
      organizationUserRepository,
      projectRepository,
      projectUserRepository,
      clientRepository,
      userRepository,
      userIdentityRepository
    )
  lazy val oauthClientReadAdapter: OAuthClientReadAdapter =
    new OAuthClientReadAdapter(transactor, organizationRepository, clientRepository, projectRepository)
  lazy val oauthProjectReadAdapter: OAuthProjectReadAdapter =
    new OAuthProjectReadAdapter(transactor, organizationRepository, projectRepository, projectUserRepository, clientRepository, userRepository)
  lazy val oauthWorkUnitReadAdapter: OAuthWorkUnitReadAdapter =
    new OAuthWorkUnitReadAdapter(transactor, organizationRepository, workUnitRepository, userRepository, workUnitDtoService)
  lazy val oauthReportAdapter: OAuthReportAdapter =
    new OAuthReportAdapter(transactor, organizationRepository, projectRepository, workUnitPivotTableService, workUnitPivotQueryService)
  lazy val oauthExportAdapter: OAuthExportAdapter =
    new OAuthExportAdapter(
      transactor,
      organizationRepository,
      projectRepository,
      clientRepository,
      userRepository,
      workUnitRepository,
      workUnitPivotQueryService,
      exportWorkUnitPivotTableService
    )

  private lazy val workUnitIdempotencyRedactJob: WorkUnitIdempotencyRedactJob = {
    val _                    = workUnitIdempotencyConfig
    val schedulableJobConfig = SchedulableJobConfig(config.getConfig("workUnit.idempotency.redactSchedulableJob"))

    wire[WorkUnitIdempotencyRedactJob]
  }

  lazy val workUnitRouter: WorkUnitRouter = wire[WorkUnitRouter]
  private lazy val oauthApiRouter = new OAuthApiRouter(
    oauthOrganizationReadAdapter,
    oauthUserReadAdapter,
    oauthClientReadAdapter,
    oauthProjectReadAdapter,
    oauthWorkUnitReadAdapter,
    oauthWorkUnitWriteAdapter,
    oauthReportAdapter,
    oauthExportAdapter,
    pivotExportAdmissionController
  )

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ workUnitOverrideRouter.authRoutes(auth) ~ workUnitRouter.authRoutes(auth)

  abstract override def oauthRoute(auth: OAuthAuthContext)(implicit tracingContext: TracingContext): Route =
    super.oauthRoute(auth) ~ oauthApiRouter.routes(auth)

  abstract override def schedulableJobs: List[SchedulableJob] = workUnitIdempotencyRedactJob :: super.schedulableJobs
}
