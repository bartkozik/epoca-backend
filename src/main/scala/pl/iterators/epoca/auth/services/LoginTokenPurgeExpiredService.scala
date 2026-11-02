package pl.iterators.epoca.auth.services

import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthDomain.LoginTokenValidTo
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.utils.clock.ClockExtension
import pl.iterators.epoca.utils.db.PostgresIOTransactor

class LoginTokenPurgeExpiredService(transactor: PostgresIOTransactor, loginTokenRepository: UserLoginTokenRepository) extends ClockExtension {
  def purge(): IO[Unit] =
    Clock[IO].instantNow.flatMap { now =>
      transactor.execute(loginTokenRepository.purge(UserLoginTokenFilters(validToBefore = Some(LoginTokenValidTo(now)))))
    }
}
