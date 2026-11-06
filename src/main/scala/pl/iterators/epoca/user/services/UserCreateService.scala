package pl.iterators.epoca.user.services

import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.auth.domain.AuthDomain.{LoginTokenPlain, PasswordPlain}
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.services.LoginTokenGetService.LoginTokenGetRequest
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}
import pl.iterators.epoca.auth.services.{LoginTokenGenerateService, LoginTokenGetService, PasswordService, PasswordValidateService}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.services.UserCreateService.{UserCreateRequest, UserCreateResult, UserCreateResultData, UserCreateSamlRequest}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.security.SecretComparison
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

import java.time.Instant
import java.util.UUID

class UserCreateService(
  config: UserCreateService.Config,
  transactor: PostgresIOTransactor,
  userCreationActions: UserCreationActions,
  userLoginTokenRepository: UserLoginTokenRepository,
  loginTokenGetService: LoginTokenGetService,
  passwordService: PasswordService,
  passwordValidateService: PasswordValidateService,
  loginTokenGenerateService: LoginTokenGenerateService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserCreateResult](ioRuntime)
    with LoggingSupport {

  def create(request: UserCreateRequest): IO[UserCreateResult] =
    (for {
      _            <- validateRequest(request)
      isSuperAdmin <- validateSuperAdminKey(request)
      _            <- validatePassword(request)
      _            <- Logger[IO].info(s"Creating user ${request.email} ${request.phoneNumber}").seal
      now          <- getNow
      user         <- createUser(request, isSuperAdmin, now)
      identity     <- createUserIdentity(user, request, now)
      passwordHash <- IO.blocking(request.password.map(passwordService.encrypt)).seal
      created <-
        transactor.executeTransactionally(userCreationActions.create(user, identity, passwordHash)).valueOr(UserCreateResult.IdentityAlreadyExist)
      (inserted, insertedIdentity) = created
      _             <- sendToken(request)
      recoveryCodes <- createRecoveryCodes(insertedIdentity)
      _             <- Logger[IO].info(s"Creating user result $inserted").seal
    } yield UserCreateResult.Ok(UserCreateResultData(inserted, recoveryCodes))).run

  def createWithSaml(request: UserCreateSamlRequest): IO[UserCreateResult] =
    (for {
      now      <- getNow
      user     <- createSamlUser(request, now)
      identity <- createSamlIdentity(user, request, now)
      created  <- transactor.executeTransactionally(userCreationActions.create(user, identity, None)).valueOr(UserCreateResult.IdentityAlreadyExist)
      (inserted, _) = created
      _ <- Logger[IO].info(s"Creating user result $inserted").seal
    } yield UserCreateResult.Ok(UserCreateResultData(inserted, Seq.empty))).run

  private def validateRequest(request: UserCreateRequest): StepIO[Boolean] =
    Sync[IO]
      .pure(
        (request.email.isDefined && request.phoneNumber.isEmpty) ||
          (request.email.isEmpty && request.phoneNumber.isDefined)
      )
      .ensureF(identity, Logger[IO].warning(s"User with provided credentials already exists").as(UserCreateResult.InvalidRequest))

  private def validateSuperAdminKey(request: UserCreateRequest): StepIO[Boolean] =
    request.superAdminKey.filter(_.nonEmpty) match {
      case None => Sync[IO].pure(false).seal
      case Some(provided) =>
        Sync[IO]
          .pure(config.superAdminSecretKey.exists(SecretComparison.matches(provided, _)))
          .ensureF(identity, Logger[IO].warning("Rejected user creation with an invalid super admin key").as(UserCreateResult.SuperAdminKeyRejected))
    }

  private def validatePassword(request: UserCreateRequest): StepIO[Unit] =
    request.password
      .map { password =>
        Sync[IO]
          .delay(passwordValidateService.validate(password))
          .seal
          .attempt {
            case PasswordValidateResult.Valid             => Right(())
            case PasswordValidateResult.Invalid(problems) => Left(UserCreateResult.InvalidPassword(problems))
          }
      }
      .getOrElse(Sync[IO].pure(()).seal)

  private def getNow: StepIO[Instant] =
    Clock[IO].instantNow.seal

  private def createUser(
    request: UserCreateRequest,
    isSuperAdmin: Boolean,
    now: Instant
  ): StepIO[User] =
    Sync[IO].pure {
      User(
        id = UserId(UUID.randomUUID),
        firstName = request.firstName,
        lastName = request.lastName,
        avatar = request.avatar,
        color = request.color,
        isSuperAdmin = UserIsSuperAdmin(isSuperAdmin),
        createdAt = UserCreatedAt(now),
        modifiedAt = UserModifiedAt(now),
        archivedAt = None
      )
    }.seal

  private def createSamlUser(request: UserCreateSamlRequest, now: Instant): StepIO[User] =
    Sync[IO].pure {
      User(
        id = UserId(UUID.randomUUID),
        firstName = request.firstName,
        lastName = request.lastName,
        avatar = None,
        color = None,
        isSuperAdmin = UserIsSuperAdmin(false),
        createdAt = UserCreatedAt(now),
        modifiedAt = UserModifiedAt(now),
        archivedAt = None
      )
    }.seal

  private def createUserIdentity(
    user: User,
    request: UserCreateRequest,
    now: Instant
  ): StepIO[UserIdentity] =
    Sync[IO]
      .pure(
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID),
          userId = user.id,
          `type` = if (request.email.isDefined) IdentityType.MailAddress else IdentityType.Phone,
          value = UserIdentityValue(request.email.orElse(request.phoneNumber).getOrElse("")),
          deviceId = None,
          main = UserIdentityMain(true),
          confirmed = UserIdentityConfirmed(user.isSuperAdmin),
          confirmationCode = None,
          createdAt = UserIdentityCreatedAt(now)
        )
      )
      .seal

  private def createSamlIdentity(
    user: User,
    request: UserCreateSamlRequest,
    now: Instant
  ): StepIO[UserIdentity] =
    Sync[IO]
      .pure(
        UserIdentity(
          id = UserIdentityId(UUID.randomUUID),
          userId = user.id,
          `type` = IdentityType.Saml,
          value = UserIdentityValue(request.email),
          deviceId = None,
          main = UserIdentityMain(true),
          confirmed = UserIdentityConfirmed(true),
          confirmationCode = None,
          createdAt = UserIdentityCreatedAt(now)
        )
      )
      .seal

  private def sendToken(request: UserCreateRequest): StepIO[Unit] =
    if (request.doNotSendLoginToken.contains(true)) IO.pure(()).seal
    else
      loginTokenGetService
        .getToken(LoginTokenGetRequest(email = request.email, phoneNumber = request.phoneNumber, additionalUrlParams = None))
        .void
        .seal

  private def createRecoveryCodes(identity: UserIdentity): StepIO[List[LoginTokenPlain]] =
    (0 until config.recoveryCount).toList.traverse { _ =>
      for {
        generated <- loginTokenGenerateService.generateToken(identity, expiring = false)
        _         <- transactor.execute(userLoginTokenRepository.insert(generated.token))
      } yield generated.loginTokenPlain
    }.seal
}

object UserCreateService {

  final case class Config(superAdminSecretKey: Option[String], recoveryCount: Int)

  final case class UserCreateRequest(
    firstName: UserFirstName,
    lastName: UserLastName,
    avatar: Option[UserAvatar],
    color: Option[UserColor],
    email: Option[MailAddress],
    phoneNumber: Option[PhoneNumber],
    password: Option[PasswordPlain],
    superAdminKey: Option[String],
    doNotSendLoginToken: Option[Boolean])

  final case class UserCreateSamlRequest(
    firstName: UserFirstName,
    lastName: UserLastName,
    email: MailAddress)

  object Config {
    val MinimumSuperAdminKeyLength = 32
    private val SuperAdminKeyPath  = "user.createAdminSecretKey"

    def apply(config: TsConfig): Config =
      Config(superAdminSecretKey = superAdminKey(config), recoveryCount = config.getInt("user.loginToken.recoveryCount"))

    private def superAdminKey(config: TsConfig): Option[String] =
      if (config.hasPath(SuperAdminKeyPath)) Some(config.getString(SuperAdminKeyPath)).filter(_.trim.length >= MinimumSuperAdminKeyLength)
      else None
  }

  final case class UserCreateResultData(user: User, recoveryCodes: Seq[LoginTokenPlain])

  sealed trait UserCreateResult
  object UserCreateResult {
    case class Ok(user: UserCreateResultData)                          extends UserCreateResult
    case object IdentityAlreadyExist                                   extends UserCreateResult
    case class InvalidPassword(problems: Set[PasswordValidateProblem]) extends UserCreateResult
    case object InvalidRequest                                         extends UserCreateResult
    case object SuperAdminKeyRejected                                  extends UserCreateResult
  }
}
