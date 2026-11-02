package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import com.google.firebase.auth.{FirebaseAuth, FirebaseToken}
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByFirebaseService.{LoginByFirebaseRequest, LoginByFirebaseResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateRequest, UserCreateResult}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.user.services.{AvatarSyncService, UserCreateService, UserWithIdentitiesFindService}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

import scala.util.Try

class LoginByFirebaseService(
  transactor: PostgresIOTransactor,
  firebaseAuthProvider: FirebaseAuthProvider,
  loginResolveService: LoginResolveService,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  organizationUserRepository: OrganizationUserRepository,
  userIdentityRepository: UserIdentityRepository,
  userRepository: UserRepository,
  userCreateService: UserCreateService,
  avatarSyncService: AvatarSyncService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginByFirebaseResult](ioRuntime)
    with LoggingSupport {

  def login(
    prevAuthContext: Option[AuthContext],
    request: LoginByFirebaseRequest,
    requestContext: LoginHttpRequestContext
  ): IO[LoginByFirebaseResult] =
    (for {
      firebaseAuth       <- getFirebaseAuth
      firebaseToken      <- verifyFirebaseIdToken(firebaseAuth, request)
      userOpt            <- findUser(prevAuthContext, firebaseToken)
      user               <- createUserIfMissing(userOpt, firebaseToken)
      userWithIdentities <- findUserWithIdentities(user)
      identity           <- findUserIdentity(userWithIdentities, firebaseToken)
      _                  <- confirmIdentity(identity)
      orgUsers           <- listOrgUsers(user.id)
      result             <- loginResolve(requestContext, userWithIdentities, orgUsers, prevAuthContext, identity, firebaseToken.isEmailVerified)
      _                  <- syncAvatarInBackground(user, firebaseToken)
    } yield LoginByFirebaseResult.Ok(result)).run

  private def getFirebaseAuth: StepIO[FirebaseAuth] =
    IO.delay(firebaseAuthProvider.firebaseAuth).valueOr(LoginByFirebaseResult.FirebaseNotConfigured)

  private def verifyFirebaseIdToken(firebaseAuth: FirebaseAuth, request: LoginByFirebaseRequest): StepIO[FirebaseToken] =
    IO.delay(Try(firebaseAuth.verifyIdToken(request.firebaseToken)).toOption)
      .valueOr(LoginByFirebaseResult.InvalidCredentials)
      .ensure(_.isEmailVerified, LoginByFirebaseResult.InvalidCredentials)

  private def findUser(prevAuthContext: Option[AuthContext], token: FirebaseToken): StepIO[Option[User]] =
    transactor
      .execute(
        userRepository
          .find(UserFilters(email = Some(MailAddress(token.getEmail.toLowerCase))))
      )
      .ensure(userOpt => prevAuthContext.forall(auth => userOpt.exists(_.id == auth.id)), LoginByFirebaseResult.InvalidCredentials)

  private def createUserIfMissing(userOpt: Option[User], firebaseToken: FirebaseToken): StepIO[User] = {
    lazy val userCreateRequest = UserCreateRequest(
      firstName = UserFirstName(firebaseToken.getName),
      lastName = UserLastName(firebaseToken.getName), // TODO figure out how to split it
      avatar = None,
      color = None,
      email = Some(MailAddress(firebaseToken.getEmail.toLowerCase)),
      phoneNumber = None,
      password = None,
      superAdminKey = None,
      doNotSendLoginToken = Some(true)
    )
    userOpt.fold {
      userCreateService
        .create(userCreateRequest)
        .seal
        .attempt {
          case UserCreateResult.Ok(user) => Right(user.user)
          case _                         => Left(LoginByFirebaseResult.InvalidCredentials)
        }
    }(user => IO.pure(user).seal)
  }

  /** Best effort, started only once the login is accepted: runs in a background fiber, so it neither delays nor can fail the login. */
  private def syncAvatarInBackground(user: User, firebaseToken: FirebaseToken): StepIO[Unit] =
    LoginByFirebaseService.googlePictureUrl(firebaseToken) match {
      case Some(pictureUrl) =>
        IO.defer(avatarSyncService.schedule(user, pictureUrl))
          .handleErrorWith(_ => Logger[IO].warning("Google photo dispatch failed"))
          .seal
      case None => IO.unit.seal
    }

  private def findUserWithIdentities(user: User): StepIO[UserWithIdentities] = userWithIdentitiesFindService.find(user.id).attemptF {
    case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
    case UserWithIdentitiesFindResult.UserDoNotExist =>
      Logger[IO].warning(s"User ${user.id} does not exist") *> Sync[IO].pure(Left(LoginByFirebaseResult.InvalidCredentials))
  }

  private def findUserIdentity(userWithIdentities: UserWithIdentities, firebaseToken: FirebaseToken): StepIO[UserIdentity] =
    IO.pure(
      userWithIdentities.identities.find(i => i.value.toLowerCase == firebaseToken.getEmail.toLowerCase && i.`type` == IdentityType.MailAddress)
    ).valueOr(LoginByFirebaseResult.InvalidCredentials)

  private def confirmIdentity(identity: UserIdentity): StepIO[Unit] =
    if (!identity.confirmed)
      transactor.execute(userIdentityRepository.update(identity.copy(confirmed = UserIdentityConfirmed(true)))).map(_ => ()).seal
    else Sync[IO].pure(()).seal

  private def listOrgUsers(userId: UserId): StepIO[Seq[OrganizationUser]] =
    transactor.execute {
      organizationUserRepository.list(OrganizationUserFilters(userId = Some(userId)))
    }.seal

  private def loginResolve(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    prevAuthContext: Option[AuthContext],
    userIdentity: UserIdentity,
    verifiedEmail: Boolean
  ): StepIO[LoginResolution] =
    loginResolveService
      .resolve(UserLogin(requestContext, user, orgUsers, prevAuthContext, userIdentity, Option.when(verifiedEmail)(CredentialMethod.Firebase)))
      .seal

}

object LoginByFirebaseService {

  /** The profile picture to sync, only for tokens issued for a Google sign-in (`firebase.sign_in_provider` = `google.com`). */
  private[services] def googlePictureUrl(token: FirebaseToken): Option[String] = googlePictureUrl(token.getClaims, token.getPicture)

  private[services] def googlePictureUrl(claims: java.util.Map[String, AnyRef], picture: String): Option[String] = {
    val signInProvider = Option(claims)
      .flatMap(c => Option(c.get("firebase")))
      .collect { case m: java.util.Map[_, _] =>
        Option(m.get("sign_in_provider"))
      }
      .flatten
    if (signInProvider.contains("google.com")) Option(picture).map(_.trim).filter(_.nonEmpty) else None
  }

  final case class LoginByFirebaseRequest(firebaseToken: String)

  sealed trait LoginByFirebaseResult
  object LoginByFirebaseResult {
    final case class Ok(data: LoginResolution) extends LoginByFirebaseResult
    final case object FirebaseNotConfigured    extends LoginByFirebaseResult
    final case object InvalidCredentials       extends LoginByFirebaseResult
  }
}
