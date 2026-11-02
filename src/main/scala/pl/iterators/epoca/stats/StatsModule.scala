package pl.iterators.epoca.stats

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.stats.repositories.{WorkUnitPivotQueryRepository, WorkUnitPivotQueryRepositoryImpl}
import pl.iterators.epoca.stats.routers.StatsRouter
import pl.iterators.epoca.stats.services.{
  WorkUnitPivotQueryAssembler,
  WorkUnitPivotQueryRequestValidator,
  WorkUnitPivotQueryService,
  WorkUnitPivotTableService
}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository

import scala.concurrent.ExecutionContext

trait StatsModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val workUnitRepository: WorkUnitRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository

  lazy val workUnitPivotTableService: WorkUnitPivotTableService = wire[WorkUnitPivotTableService]

  lazy val workUnitPivotQueryRepository: WorkUnitPivotQueryRepository             = wire[WorkUnitPivotQueryRepositoryImpl]
  lazy val workUnitPivotQueryRequestValidator: WorkUnitPivotQueryRequestValidator = wire[WorkUnitPivotQueryRequestValidator]
  lazy val workUnitPivotQueryAssembler: WorkUnitPivotQueryAssembler               = wire[WorkUnitPivotQueryAssembler]
  lazy val workUnitPivotQueryConfig: WorkUnitPivotQueryService.Config             = WorkUnitPivotQueryService.Config(config)
  lazy val workUnitPivotQueryService: WorkUnitPivotQueryService                   = wire[WorkUnitPivotQueryService]

  lazy val statsRouter: StatsRouter = wire[StatsRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = super.route(auth) ~ statsRouter.authRoutes(auth)
}
