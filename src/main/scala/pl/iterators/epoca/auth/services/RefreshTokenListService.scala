package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AuthContext, RefreshToken}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.services.RefreshTokenListService.{RefreshTokenDto, RefreshTokenListResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import slick.dbio.DBIO

class RefreshTokenListService(transactor: PostgresIOTransactor, refreshTokenRepository: RefreshTokenRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[RefreshTokenListResult](ioRuntime) {

  def list(authContext: AuthContext): IO[RefreshTokenListResult] =
    transactor.execute {
      for {
        tokens <- doList(authContext)
        result = prepareResult(tokens)
      } yield RefreshTokenListResult.Ok(result)
    }

  private def doList(authContext: AuthContext): DBIO[Seq[RefreshToken]] =
    refreshTokenRepository.list(RefreshTokenFilters(userId = Some(authContext.id)))

  private def prepareResult(tokens: Seq[RefreshToken]): Seq[RefreshTokenDto] =
    tokens.map(RefreshTokenDto(_))
}

object RefreshTokenListService {
  sealed trait RefreshTokenListResult

  final case class RefreshTokenDto(
    deviceId: DeviceId,
    userAgent: Option[RefreshTokenUserAgent],
    lastAuthedAt: RefreshTokenLastUsedAt,
    createdAt: RefreshTokenCreatedAt,
    expiredAt: Option[RefreshTokenExpiredAt])

  object RefreshTokenDto {
    def apply(refreshToken: RefreshToken): RefreshTokenDto =
      refreshToken.into[RefreshTokenDto].transform
  }

  object RefreshTokenListResult {
    case class Ok(tokens: Seq[RefreshTokenDto]) extends RefreshTokenListResult
  }
}
