package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.services.RefreshTokenDeleteService.{RefreshTokenDeleteRequest, RefreshTokenDeleteResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import slick.dbio.DBIO

class RefreshTokenDeleteService(transactor: PostgresIOTransactor, refreshTokenRepository: RefreshTokenRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[RefreshTokenDeleteResult](ioRuntime) {

  def delete(authContext: AuthContext, request: RefreshTokenDeleteRequest): IO[RefreshTokenDeleteResult] =
    transactor.execute {
      for {
        _ <- purge(authContext, request)
      } yield RefreshTokenDeleteResult.Ok
    }

  private def purge(authContext: AuthContext, request: RefreshTokenDeleteRequest): DBIO[Int] =
    refreshTokenRepository.purge(authContext.id, request.deviceId)

}

object RefreshTokenDeleteService {
  sealed trait RefreshTokenDeleteResult

  final case class RefreshTokenDeleteRequest(deviceId: DeviceId)

  object RefreshTokenDeleteResult {
    case object Ok extends RefreshTokenDeleteResult
  }
}
