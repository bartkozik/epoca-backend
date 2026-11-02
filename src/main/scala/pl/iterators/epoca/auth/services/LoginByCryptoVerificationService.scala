package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import cats.implicits._
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.CryptoVerificationService.CryptoVerificationResult
import pl.iterators.epoca.auth.services.LoginByCryptoVerificationService._
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId}
import pl.iterators.epoca.user.domain.{UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIO
import pl.iterators.sealedmonad.syntax._

class LoginByCryptoVerificationService(
  transactor: PostgresIOTransactor,
  userWithIdentitiesFindService: UserWithIdentitiesFindService,
  organizationUserRepository: OrganizationUserRepository,
  cryptoVerificationService: CryptoVerificationService,
  loginResolveService: LoginResolveService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIO[LoginByCryptoVerificationResult](ioRuntime)
    with LoggingSupport {

  def login(
    prevAuthContext: Option[AuthContext],
    loginRequest: LoginByCryptoVerificationRequest,
    requestContext: LoginHttpRequestContext
  ): IO[LoginByCryptoVerificationResult] =
    (for {
      _                   <- validatePrevAuthContext(prevAuthContext, loginRequest)
      userWithIdentities  <- findUserWithIdentities(loginRequest)
      publicKeyIdentities <- filterPublicKeys(userWithIdentities)
      identity            <- verify(publicKeyIdentities, loginRequest)
      orgUsers            <- listOrgUsers(userWithIdentities.id)
      result              <- loginResolve(requestContext, userWithIdentities, orgUsers, prevAuthContext, identity)
    } yield LoginByCryptoVerificationResult.Ok(result)).run

  private def validatePrevAuthContext(prevAuthContext: Option[AuthContext], loginRequest: LoginByCryptoVerificationRequest): Step[Boolean] =
    Sync[IO]
      .pure(prevAuthContext.forall(_.id == loginRequest.userId))
      .seal
      .ensure(identity, LoginByCryptoVerificationResult.InvalidCredentials)

  private def findUserWithIdentities(request: LoginByCryptoVerificationRequest): Step[UserWithIdentities] =
    userWithIdentitiesFindService.find(request.userId).attemptF {
      case UserWithIdentitiesFindResult.Ok(user) => Sync[IO].pure(Right(user))
      case UserWithIdentitiesFindResult.UserDoNotExist =>
        Logger[IO].warning(s"User ${request.userId} does not exist") *> Sync[IO].pure(Left(LoginByCryptoVerificationResult.UserNotFound))
    }

  private def filterPublicKeys(user: UserWithIdentities): Step[Seq[UserIdentity]] =
    Sync[IO]
      .pure(user.identities.filter(i => i.`type` == IdentityType.CryptoVerification))
      .seal
      .ensure(_.nonEmpty, LoginByCryptoVerificationResult.InvalidCredentials)

  private def verify(identities: Seq[UserIdentity], loginRequest: LoginByCryptoVerificationRequest): Step[UserIdentity] =
    identities.toList
      .traverse(identity =>
        cryptoVerificationService
          .verify(
            loginRequest.algorithm.getOrElse(SignatureAlgorithm.default),
            Base64PublicKey(identity.value),
            loginRequest.signature,
            CryptoVerificationData(identity.userId.toString)
          )
          .map(result => identity -> result)
      )
      .map(_.find(_._2 == CryptoVerificationResult.Verified).map(_._1))
      .valueOr(LoginByCryptoVerificationResult.InvalidCredentials)

  private def listOrgUsers(userId: UserId): Step[Seq[OrganizationUser]] =
    transactor.execute {
      organizationUserRepository.list(OrganizationUserFilters(userId = Some(userId)))
    }.seal

  private def loginResolve(
    requestContext: LoginHttpRequestContext,
    user: UserWithIdentities,
    orgUsers: Seq[OrganizationUser],
    prevAuthContext: Option[AuthContext],
    userIdentity: UserIdentity
  ): Step[LoginResolution] =
    loginResolveService
      .resolve(UserLogin(requestContext, user, orgUsers, prevAuthContext, userIdentity, Some(CredentialMethod.CryptoVerification)))
      .seal

}

object LoginByCryptoVerificationService {
  final case class LoginByCryptoVerificationRequest(
    userId: UserId,
    signature: CryptoSignature,
    algorithm: Option[SignatureAlgorithm])

  sealed trait LoginByCryptoVerificationResult
  object LoginByCryptoVerificationResult {
    final case class Ok(data: LoginResolution) extends LoginByCryptoVerificationResult
    final case object UserNotFound             extends LoginByCryptoVerificationResult
    final case object InvalidCredentials       extends LoginByCryptoVerificationResult
  }

}
