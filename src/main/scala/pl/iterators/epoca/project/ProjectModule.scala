package pl.iterators.epoca.project

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectRepositoryImpl, ProjectUserRepository, ProjectUserRepositoryImpl}
import pl.iterators.epoca.project.routers.ProjectRouter
import pl.iterators.epoca.project.services._
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait ProjectModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val organizationRepository: OrganizationRepository
  val clientRepository: ClientRepository
  val organizationUserRepository: OrganizationUserRepository
  val userRepository: UserRepository

  lazy val projectRepository: ProjectRepository         = wire[ProjectRepositoryImpl]
  lazy val projectUserRepository: ProjectUserRepository = wire[ProjectUserRepositoryImpl]

  lazy val projectCreateService: ProjectCreateService             = wire[ProjectCreateService]
  lazy val projectDtoService: ProjectDtoService                   = wire[ProjectDtoService]
  lazy val projectListService: ProjectListService                 = wire[ProjectListService]
  lazy val projectFindService: ProjectFindService                 = wire[ProjectFindService]
  lazy val projectUpdateService: ProjectUpdateService             = wire[ProjectUpdateService]
  lazy val projectDeleteService: ProjectDeleteService             = wire[ProjectDeleteService]
  lazy val projectAssignService: ProjectAssignService             = wire[ProjectAssignService]
  lazy val projectDeassignService: ProjectDeassignService         = wire[ProjectDeassignService]
  lazy val projectBulkAssignService: ProjectBulkAssignService     = wire[ProjectBulkAssignService]
  lazy val projectBulkDeassignService: ProjectBulkDeassignService = wire[ProjectBulkDeassignService]

  lazy val projectRouter: ProjectRouter = wire[ProjectRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = super.route(auth) ~ projectRouter.authRoutes(auth)
}
