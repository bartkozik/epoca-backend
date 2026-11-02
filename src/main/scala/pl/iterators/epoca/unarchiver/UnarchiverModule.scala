package pl.iterators.epoca.unarchiver

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.unarchiver.routers.{ClientUnarchiverRouter, ProjectUnarchiverRouter}
import pl.iterators.epoca.unarchiver.services.{ClientUnarchiveService, ProjectUnarchiveService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait UnarchiverModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val clientRepository: ClientRepository
  val projectRepository: ProjectRepository
  val projectUserRepository: ProjectUserRepository
  val organizationRepository: OrganizationRepository
  val organizationUserRepository: OrganizationUserRepository

  lazy val clientUnarchiveService: ClientUnarchiveService   = wire[ClientUnarchiveService]
  lazy val projectUnarchiveService: ProjectUnarchiveService = wire[ProjectUnarchiveService]

  private lazy val clientUnarchiverRouter: ClientUnarchiverRouter   = wire[ClientUnarchiverRouter]
  private lazy val projectUnarchiverRouter: ProjectUnarchiverRouter = wire[ProjectUnarchiverRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ clientUnarchiverRouter.authRoutes(auth) ~ projectUnarchiverRouter.authRoutes(auth)
}
