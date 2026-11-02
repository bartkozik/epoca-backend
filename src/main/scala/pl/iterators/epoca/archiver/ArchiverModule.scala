package pl.iterators.epoca.archiver

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.archiver.routers.ClientArchiverRouter
import pl.iterators.epoca.archiver.services.ClientDeleteService
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait ArchiverModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val clientRepository: ClientRepository
  val projectRepository: ProjectRepository
  val projectUserRepository: ProjectUserRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository

  lazy val clientDeleteService: ClientDeleteService = wire[ClientDeleteService]

  private lazy val clientArchiverRouter: ClientArchiverRouter = wire[ClientArchiverRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ clientArchiverRouter.authRoutes(auth)
}
