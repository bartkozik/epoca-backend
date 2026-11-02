package pl.iterators.epoca.organizationdashboard

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.routers.OrganizationDashboardRouter
import pl.iterators.epoca.organizationdashboard.services.{OrganizationUserDetailsService, OrganizationUserListService}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait OrganizationDashboardModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository
  val projectRepository: ProjectRepository
  val clientRepository: ClientRepository
  val projectUserRepository: ProjectUserRepository
  val userRepository: UserRepository
  val userIdentityRepository: UserIdentityRepository

  lazy val organizationUserDetailsService: OrganizationUserDetailsService = wire[OrganizationUserDetailsService]

  lazy val organizationUserListService: OrganizationUserListService = wire[OrganizationUserListService]
  lazy val organizationDashboardRouter: OrganizationDashboardRouter = wire[OrganizationDashboardRouter]
  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ organizationDashboardRouter.authRoutes(auth)
}
