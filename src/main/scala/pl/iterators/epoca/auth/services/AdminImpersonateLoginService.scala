package pl.iterators.epoca.auth.services

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Sync}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.AdminImpersonateLoginService._
import pl.iterators.epoca.auth.services.LoginResolveService.{AdminImpersonate, LoginResolution}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIO
import pl.iterators.sealedmonad.syntax._

import java.time.Duration

class AdminImpersonateLoginService(
  transactor: PostgresIOTransactor,
  organizationUserRepository: OrganizationUserRepository,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  loginResolveService: LoginResolveService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIO[AdminImpersonateLoginResult](ioRuntime)
    with JsonProtocol
    with LoggingSupport {

  def login(
    authContext: AuthContext,
    request: AdminImpersonateLoginRequest,
    requestContext: LoginHttpRequestContext
  ): IO[AdminImpersonateLoginResult] = (for {
    _ <- checkPermissions(authContext)
    _ <- IO.pure(LoginResolveService.isAllowedImpersonationDuration(request.validFor))
           .seal
           .ensure(identity, AdminImpersonateLoginResult.OperationNotPermitted)
    user     <- findUser(request.userId)
    orgUsers <- listOrgUsers(request.userId)
    result   <- loginResolve(requestContext, user, orgUsers, request.validFor)
    _        <- Logger[IO].info(s"Admin ${authContext.id} generated jwt for user ${user.id}").seal
  } yield AdminImpersonateLoginResult.Ok(result)).run

  private def checkPermissions(context: AuthContext): Step[AuthContext] =
    IO
      .pure(context)
      .ensureF(
        _.isAdmin,
        Logger[IO].warning(s"User ${context.id} don't have permission to create arbitrary JWT") *> IO.pure(
          AdminImpersonateLoginResult.OperationNotPermitted
        )
      )

  private def findUser(id: UserId): Step[UserWithIdentities] = userWithIdentitiesFindService.find(id).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User $id does not exist").as(Left(AdminImpersonateLoginResult.UserDoesNotExist))
  }

  private def listOrgUsers(userId: UserId): Step[Seq[OrganizationUser]] =
    transactor.execute {
      organizationUserRepository.list(OrganizationUserFilters(userId = Some(userId)))
    }.seal

  private def loginResolve(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    validFor: Duration
  ): Step[LoginResolution] =
    loginResolveService.resolve(AdminImpersonate(requestContext, user, orgUsers, validFor)).seal
}
object AdminImpersonateLoginService {
  final case class AdminImpersonateLoginRequest(userId: UserId, validFor: Duration)

  sealed trait AdminImpersonateLoginResult
  object AdminImpersonateLoginResult {
    final case class Ok(result: LoginResolution) extends AdminImpersonateLoginResult
    final case object UserDoesNotExist           extends AdminImpersonateLoginResult
    final case object OperationNotPermitted      extends AdminImpersonateLoginResult
  }
}
