package pl.iterators.epoca.user.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain._
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.services.UserIdentityListService.UserIdentityListResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
class UserIdentityListService(transactor: PostgresIOTransactor, userIdentityRepository: UserIdentityRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[UserIdentityListResult](ioRuntime)
    with LoggingSupport {

  def list(authContext: AuthContext): IO[UserIdentityListResult] = {
    val query = (for {
      identities <- list(authContext.id)
      dtos = identities.map(UserIdentityDto.from)
    } yield UserIdentityListResult.Ok(dtos)).run

    transactor.execute(query)
  }

  private def list(userId: UserId): StepDBIO[Seq[UserIdentity]] =
    userIdentityRepository.list(UserIdentityFilters(userId = Some(userId))).seal
}

object UserIdentityListService {
  sealed trait UserIdentityListResult
  object UserIdentityListResult {
    case class Ok(identities: Seq[UserIdentityDto]) extends UserIdentityListResult
  }
}
