package pl.iterators.epoca.invitation.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.repositories.InvitationRepository
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.invitation.services.InvitationListService._
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.sealedmonad.syntax._

class InvitationListService(transactor: PostgresIOTransactor, invitationRepository: InvitationRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[InvitationListResult](ioRuntime)
    with LoggingSupport {

  def list(authContext: AuthContext): IO[InvitationListResult] =
    (for {
      filters <- prepareFilters(authContext)
      page    <- listInvitationsWithCount(filters)
    } yield InvitationListResult.Ok(page)).run

  private def prepareFilters(authContext: AuthContext): StepIO[InvitationFilters] =
    Clock[IO].instantNow.map { instant =>
      InvitationFilters(
        userId = Some(authContext.id),
        acceptableOn = Some(instant),
        protocolVersion = Some(1),
        boundSaml = authContext.accountAuthentication.flatMap(_.evidence match {
          case bound: pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml => Some(bound)
          case _                                                                     => None
        })
      )
    }.seal

  private def listInvitationsWithCount(filter: InvitationFilters): StepIO[PaginatedResult[Invitation]] =
    transactor
      .executeTransactionally(IsolationLevel.RepeatableRead) {
        for {
          invitations      <- invitationRepository.list(filter)
          invitationsCount <- invitationRepository.count(filter)
        } yield PaginatedResult(invitationsCount, invitations)
      }
      .seal

}

object InvitationListService {
  sealed trait InvitationListResult
  object InvitationListResult {
    final case class Ok(data: PaginatedResult[Invitation]) extends InvitationListResult
  }
}
