package pl.iterators.epoca.organization

import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.repositories._
import pl.iterators.epoca.organization.routers.OrganizationRouter
import pl.iterators.epoca.organization.services._
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.http.AuthRouteProvider
import pl.iterators.epoca.utils.log.TracingContext

import scala.concurrent.ExecutionContext

trait OrganizationModule extends AuthRouteProvider {
  implicit val executor: ExecutionContext
  implicit val runtime: IORuntime
  val config: Config
  val transactor: PostgresIOTransactor

  val userRepository: UserRepository
  val userIdentityRepository: UserIdentityRepository

  lazy val organizationRepository: OrganizationRepository         = wire[OrganizationRepositoryImpl]
  lazy val organizationUserRepository: OrganizationUserRepository = wire[OrganizationUserRepositoryImpl]

  lazy val organizationCreateServiceConfig: OrganizationCreateService.Config = OrganizationCreateService.Config(config)
  lazy val organizationCreateService: OrganizationCreateService              = wire[OrganizationCreateService]
  lazy val organizationListService: OrganizationListService                  = wire[OrganizationListService]
  lazy val organizationFindService: OrganizationFindService                  = wire[OrganizationFindService]
  lazy val organizationUpdateService: OrganizationUpdateService              = wire[OrganizationUpdateService]
  lazy val organizationDeleteService: OrganizationDeleteService              = wire[OrganizationDeleteService]

  lazy val organizationUserAssignService: OrganizationUserAssignService         = wire[OrganizationUserAssignService]
  lazy val organizationUserDeassignService: OrganizationUserDeassignService     = wire[OrganizationUserDeassignService]
  lazy val organizationUserMembershipService: OrganizationUserMembershipService = wire[OrganizationUserMembershipService]

  lazy val organizationRouter: OrganizationRouter = wire[OrganizationRouter]

  abstract override def route(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    super.route(auth) ~ organizationRouter.authRoutes(auth)

}
