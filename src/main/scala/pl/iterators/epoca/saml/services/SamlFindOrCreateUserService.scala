package pl.iterators.epoca.saml.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlConfig}
import pl.iterators.epoca.saml.services.SamlFindOrCreateUserService.SamlFindOrCreateUserResult
import pl.iterators.epoca.saml.services.SamlFindOrCreateUserService.SamlFindOrCreateUserResult._
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.EmailIdentityRepository
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.services.UserCreateService
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateResult, UserCreateSamlRequest}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIO
import pl.iterators.sealedmonad.syntax._

class SamlFindOrCreateUserService(
  transactor: PostgresIOTransactor,
  emailIdentityRepository: EmailIdentityRepository,
  userCreateService: UserCreateService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIO[SamlFindOrCreateUserResult](ioRuntime)
    with LoggingSupport {
  def findOrCreateUser(authContext: SamlAuthContext, samlConfig: SamlConfig): IO[SamlFindOrCreateUserResult] = {
    val query: Step[SamlFindOrCreateUserResult] = for {
      _     <- tryFindUser(authContext)
      _     <- canInitializeUser(samlConfig)
      names <- extractNames(authContext)
      (firstName, lastName) = names
      user <- runCreateUserService(authContext, firstName, lastName)
    } yield Created(user)
    query.run
  }

  private def tryFindUser(authContext: SamlAuthContext): Step[Unit] =
    transactor
      .execute {
        emailIdentityRepository.resolve(authContext.email)
      }
      .attemptF {
        case Resolution.Single(user, identities)
            if user.accountType == UserAccountType.Human && user.archivedAt.isEmpty && identities.exists(_.confirmed) =>
          Logger[IO].info(s"user found ${authContext.email}").as(Left(Found(user)))
        case Resolution.Empty => Logger[IO].info(s"user not found ${authContext.email} - trying to initialize new user").as(Right(()))
        case _                => IO.pure(Left(UserCreationError))
      }

  private def canInitializeUser(samlConfig: SamlConfig): Step[Boolean] =
    samlConfig.allowAccountInit
      .liftSealed[IO, SamlFindOrCreateUserResult]
      .ensureF(identity, Logger[IO].error("failed creating user - organization has user initialization disabled").as(InitializationDisabled))

  private def extractNames(context: SamlAuthContext): Step[(String, String)] =
    (context.firstName, context.lastName).liftSealed[IO, SamlFindOrCreateUserResult].attemptF {
      case (Some(firstName), Some(lastName)) => Sync[IO].pure(Right((firstName, lastName)))
      case _                                 => Logger[IO].warning("failed creating user - missing first or last name").as(Left(AttributesMissing))
    }

  private def runCreateUserService(
    authContext: SamlAuthContext,
    firstName: String,
    lastName: String
  ): Step[User] = {
    val request = UserCreateSamlRequest(firstName = UserFirstName(firstName), lastName = UserLastName(lastName), email = authContext.email)
    userCreateService
      .createWithSaml(request)
      .seal
      .attempt {
        case UserCreateResult.Ok(user)              => Right(user.user)
        case UserCreateResult.IdentityAlreadyExist  => Left(UserCreationError)
        case UserCreateResult.InvalidPassword(_)    => Left(UserCreationError)
        case UserCreateResult.InvalidRequest        => Left(UserCreationError)
        case UserCreateResult.SuperAdminKeyRejected => Left(UserCreationError)
      }
  }
}

object SamlFindOrCreateUserService {
  sealed trait SamlFindOrCreateUserResult
  object SamlFindOrCreateUserResult {
    case class Found(user: User)       extends SamlFindOrCreateUserResult
    case class Created(user: User)     extends SamlFindOrCreateUserResult
    case object InitializationDisabled extends SamlFindOrCreateUserResult
    case object AttributesMissing      extends SamlFindOrCreateUserResult
    case object UserCreationError      extends SamlFindOrCreateUserResult
  }
}
