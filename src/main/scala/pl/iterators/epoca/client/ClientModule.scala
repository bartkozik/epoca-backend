package pl.iterators.epoca.client

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.client.routers.ClientRouter
import pl.iterators.epoca.client.services._
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait ClientModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository
  val userRepository: UserRepository
  val projectRepository: ProjectRepository

  private lazy val clientRepositoryConfig     = ClientRepositoryImpl.Config(config)
  lazy val clientRepository: ClientRepository = new ClientRepositoryImpl(clientRepositoryConfig)

  lazy val clientCreateService: ClientCreateService = wire[ClientCreateService]
  lazy val clientDtoService: ClientDtoService       = wire[ClientDtoService]
  lazy val clientListService: ClientListService     = wire[ClientListService]
  lazy val clientUpdateService: ClientUpdateService = wire[ClientUpdateService]

  lazy val clientRouter: ClientRouter = wire[ClientRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route = super.route(auth) ~ clientRouter.authRoutes(auth)
}
