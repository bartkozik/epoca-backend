package pl.iterators.epoca.user.services

import cats.effect.kernel.Sync
import cats.effect.std.SecureRandom
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits.toFlatMapOps
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain.{DeviceId, TimeBasedOneTimePasswordSecret}
import pl.iterators.epoca.auth.services.{AccountAuthenticationValidator, TimeBasedOneTimePasswordService}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.mail.emails.UserIdentityConfirmationCodeMail
import pl.iterators.epoca.sms.domain.SmsDomain._
import pl.iterators.epoca.sms.services.SmsService
import pl.iterators.epoca.sms.services.SmsService.SmsSendResult
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class UserIdentityCreateService(
  transactor: PostgresIOTransactor,
  mailUserIdentityConfirmationCode: UserIdentityConfirmationCodeMail,
  smsService: SmsService,
  userRepository: UserRepository,
  userIdentityRepository: UserIdentityRepository,
  emailIdentityRepository: EmailIdentityRepository,
  timeBasedOneTimePasswordService: TimeBasedOneTimePasswordService,
  accountAuthenticationValidator: AccountAuthenticationValidator
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserIdentityCreateResult](ioRuntime)
    with LoggingSupport {

  def create(authContext: AuthContext, request: UserIdentityCreateRequest): IO[UserIdentityCreateResult] = {
    val query = (for {
      _                      <- validateAuthContext(authContext)
      _                      <- reserveEmail(request)
      _                      <- validateHumanUser(authContext.id)
      now                    <- Clock[DBIO].instantNow.seal
      _                      <- accountAuthenticationValidator.validate(authContext, now).ensure(identity, UserIdentityCreateResult.IdentityNotFresh)
      _                      <- validateRequest(request)
      _                      <- validateIdentityValue(request)
      _                      <- validateIdentityValueUniqueness(request)
      identities             <- listUserIdentities(authContext.id)
      currentMainIdentityOpt <- ensureMainIdentityWillBeDefined(request, identities)
      confirmationCodeOpt    <- buildConfirmationCode(request.`type`)
      newUserIdentity        <- buildUserIdentity(authContext.id, request, confirmationCodeOpt)
      insertedUserIdentity   <- insertAndUpdateMain(newUserIdentity, currentMainIdentityOpt)
    } yield UserIdentityCreateResult.Ok(insertedUserIdentity)).run

    transactor.executeTransactionally(query).flatMap {
      case UserIdentityCreateResult.Ok(userIdentity) => send(userIdentity).as(UserIdentityCreateResult.Ok(userIdentity.copy(confirmationCode = None)))
      case notOkResult                               => IO.pure(notOkResult)
    }
  }

  private def validateAuthContext(authContext: AuthContext): StepDBIO[Boolean] =
    Sync[DBIO]
      .pure(authContext.fresh && authContext.accountAuthentication.nonEmpty)
      .ensure(identity, UserIdentityCreateResult.IdentityNotFresh)

  private def validateHumanUser(userId: UserId): StepDBIO[Unit] =
    userRepository
      .find(UserFilters(id = Some(userId), forUpdate = true))
      .valueOr(UserIdentityCreateResult.InvalidRequest)
      .ensure(user => user.accountType == UserAccountType.Human && user.archivedAt.isEmpty, UserIdentityCreateResult.InvalidRequest)
      .void

  private def validateRequest(request: UserIdentityCreateRequest): StepDBIO[Boolean] =
    (request.`type` match {
      case IdentityType.MailAddress        => Sync[DBIO].pure(UserIdentityValue.isValid(request.`type`, request.value))
      case IdentityType.Phone              => Sync[DBIO].pure(UserIdentityValue.isValid(request.`type`, request.value))
      case IdentityType.CryptoVerification => Sync[DBIO].pure(request.deviceId.nonEmpty && UserIdentityValue.isValid(request.`type`, request.value))
      case IdentityType.AdminImpersonate   => Sync[DBIO].pure(false)
      case IdentityType.Saml               => Sync[DBIO].pure(false)
      case IdentityType.TimeBasedOneTimePassword =>
        if (!request.main && UserIdentityValue.isValid(request.`type`, request.value) && request.verifyToken.nonEmpty) {
          timeBasedOneTimePasswordService.generateDBIO(TimeBasedOneTimePasswordSecret(request.value)).map(request.verifyToken.contains)
        } else {
          Sync[DBIO].pure(false)
        }
    }).seal
      .ensure(identity, UserIdentityCreateResult.InvalidRequest)

  private def validateIdentityValue(request: UserIdentityCreateRequest): StepDBIO[Unit] =
    UserIdentityValue.isValid(request.`type`, request.value).liftSealed.attempt {
      case true  => Right(())
      case false => Left(UserIdentityCreateResult.InvalidRequest)
    }

  private def reserveEmail(request: UserIdentityCreateRequest): StepDBIO[Unit] =
    (if (request.`type` == IdentityType.MailAddress) emailIdentityRepository.lock(request.value)
     else DBIO.successful(())).seal

  private def validateIdentityValueUniqueness(request: UserIdentityCreateRequest): StepDBIO[Unit] =
    if (request.`type` == IdentityType.MailAddress)
      emailIdentityRepository
        .resolve(request.value)
        .ensure(_ == EmailIdentityRepository.Resolution.Empty, UserIdentityCreateResult.IdentityValueAlreadyExist)
        .void
    else
      userIdentityRepository
        .find(UserIdentityFilters(value = Some(request.value)))
        .ensure(_.isEmpty, UserIdentityCreateResult.IdentityValueAlreadyExist)
        .void

  private def listUserIdentities(userId: UserId): StepDBIO[Seq[UserIdentity]] =
    userIdentityRepository
      .list(UserIdentityFilters(userId = Some(userId), forUpdate = true))
      .seal

  private def ensureMainIdentityWillBeDefined(request: UserIdentityCreateRequest, otherIdentities: Seq[UserIdentity])
    : StepDBIO[Option[UserIdentity]] =
    otherIdentities.find(_.main).liftSealed.attempt {
      case Some(currentMainIdentity) => Right(Some(currentMainIdentity))
      case None if request.main      => Right(None)
      case _                         => Left(UserIdentityCreateResult.NoMainIdentity)
    }

  private def buildConfirmationCode(identityType: IdentityType): StepDBIO[Option[UserIdentityConfirmationCode]] =
    ((IdentityType.confirmationRequiredTypes.contains(identityType) match {
      case false => Sync[DBIO].pure(None)
      case true =>
        SecureRandom
          .javaSecuritySecureRandom[DBIO]
          .flatMap(_.betweenLong(10000L, 99999L))
          .map(code => Some(UserIdentityConfirmationCode(code.toString)))
    }): DBIO[Option[UserIdentityConfirmationCode]]).seal

  private def buildUserIdentity(
    userId: UserId,
    request: UserIdentityCreateRequest,
    confirmationCodeOpt: Option[UserIdentityConfirmationCode]
  ): StepDBIO[UserIdentity] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, UserIdentityId].map { id =>
        UserIdentity(
          id = id,
          userId = userId,
          `type` = request.`type`,
          value = request.value,
          main = request.main,
          confirmed = UserIdentityConfirmed(false),
          confirmationCode = confirmationCodeOpt,
          deviceId = request.deviceId,
          createdAt = UserIdentityCreatedAt(now)
        )
      }
    }: DBIO[UserIdentity]).seal

  private def insertAndUpdateMain(newIdentity: UserIdentity, currentMainIdentityOpt: Option[UserIdentity]): StepDBIO[UserIdentity] =
    (userIdentityRepository
      .insert(newIdentity)
      .flatTap(_ =>
        currentMainIdentityOpt match {
          case Some(currentMainIdentity) if newIdentity.main =>
            val updatedCurrentMain = currentMainIdentity.copy(main = UserIdentityMain(false))
            userIdentityRepository.update(updatedCurrentMain).map(_ => ())
          case _ => Sync[DBIO].unit
        }
      ): DBIO[UserIdentity]).seal

  private def send(userIdentity: UserIdentity): IO[Unit] =
    userIdentity.`type` match {
      case IdentityType.MailAddress              => sendEmail(userIdentity)
      case IdentityType.Phone                    => sendSms(userIdentity)
      case IdentityType.CryptoVerification       => IO.unit
      case IdentityType.AdminImpersonate         => IO.unit
      case IdentityType.TimeBasedOneTimePassword => IO.unit
      case IdentityType.Saml                     => IO.unit
    }

  private def sendEmail(userIdentity: UserIdentity): IO[Unit] =
    userIdentity.confirmationCode match {
      case Some(confirmationCode) =>
        mailUserIdentityConfirmationCode.send(UserIdentityConfirmationCodeMail.Event(MailAddress(userIdentity.value), confirmationCode))
      case None => Logger[IO].error(s"Confirmation code for ${userIdentity.`type`} not defined while sending email for user ${userIdentity.userId}")
    }

  private def sendSms(userIdentity: UserIdentity): IO[Unit] =
    userIdentity.confirmationCode match {
      case Some(confirmationCode) =>
        smsService.send(number = PhoneNumber(userIdentity.value), text = SmsText(confirmationCode)).flatMap {
          case SmsSendResult.Sent  => Logger[IO].info(s"Sms sent successfully for user identity ${userIdentity.id}")
          case SmsSendResult.Error => Logger[IO].error(s"Sms sending error for user identity ${userIdentity.id}")
        }
      case None => Logger[IO].error(s"Confirmation code for ${userIdentity.`type`} not defined while sending sms for user ${userIdentity.userId}")
    }

}

object UserIdentityCreateService {
  final case class UserIdentityCreateRequest(
    `type`: IdentityType,
    value: UserIdentityValue,
    deviceId: Option[DeviceId],
    verifyToken: Option[UserIdentityValueVerifyToken],
    main: UserIdentityMain)

  sealed trait UserIdentityCreateResult
  object UserIdentityCreateResult {
    case class Ok(identity: UserIdentity) extends UserIdentityCreateResult
    case object InvalidRequest            extends UserIdentityCreateResult
    case object IdentityNotFresh          extends UserIdentityCreateResult
    case object IdentityValueAlreadyExist extends UserIdentityCreateResult
    case object NoMainIdentity            extends UserIdentityCreateResult
  }
}
