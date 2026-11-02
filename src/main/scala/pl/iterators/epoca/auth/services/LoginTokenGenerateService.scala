package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config => TsConfig}
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.services.LoginTokenGenerateService.UserLoginTokenWithLoginTokenPlain
import pl.iterators.epoca.auth.services.LoginTokenGetService.LoginTokenGetResult
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.DurationConverters._

class LoginTokenGenerateService(config: LoginTokenGenerateService.Config, loginTokenHashService: LoginTokenHashService)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[LoginTokenGetResult](ioRuntime)
    with LoggingSupport {

  def generateToken(identity: UserIdentity, expiring: Boolean): IO[UserLoginTokenWithLoginTokenPlain] =
    for {
      loginTokenPlain <- generateLoginTokenPlain
      loginTokenHash  <- hashLoginToken(loginTokenPlain)
      token           <- prepareUserLoginToken(identity, loginTokenHash, expiring)
    } yield UserLoginTokenWithLoginTokenPlain(token, loginTokenPlain)

  private def generateLoginTokenPlain: IO[LoginTokenPlain] =
    IO.delay(LoginTokenPlain(UUID.randomUUID().toString))

  private def hashLoginToken(loginTokenPlain: LoginTokenPlain): IO[LoginTokenHash] =
    Sync[IO].delay(loginTokenHashService.hash(loginTokenPlain))

  private def prepareUserLoginToken(
    identity: UserIdentity,
    loginTokenHash: LoginTokenHash,
    expiring: Boolean
  ): IO[UserLoginToken] =
    IdGenerator
      .nextId[IO, LoginTokenId]
      .map { id =>
        UserLoginToken(
          id,
          identity.id,
          loginTokenHash,
          Option.when(expiring)(LoginTokenValidTo(Instant.ofEpochMilli(System.currentTimeMillis()).plus(config.validFor.toJava)))
        )
      }
}

object LoginTokenGenerateService {
  case class Config(validFor: FiniteDuration) {
    require(validFor > Duration.Zero, "user.loginToken.validForDuration must be positive")
  }

  object Config {
    def apply(tsConfig: TsConfig): Config =
      Config(validFor = tsConfig.getDuration("user.loginToken.validForDuration").toScala)
  }

  final case class UserLoginTokenWithLoginTokenPlain(token: UserLoginToken, loginTokenPlain: LoginTokenPlain)
}
