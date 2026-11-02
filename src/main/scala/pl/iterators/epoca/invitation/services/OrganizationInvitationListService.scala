package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.repositories.InvitationRepository
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.transactor.IsolationLevel

import scala.concurrent.ExecutionContext

class OrganizationInvitationListService(
  transactor: PostgresIOTransactor,
  invitationRepository: InvitationRepository,
  guard: InvitationAccessGuard
)(implicit ec: ExecutionContext) {
  import OrganizationInvitationListService.OrganizationInvitationListResult
  def list(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: InvitationFilters
  ): IO[OrganizationInvitationListResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead)(listAction(authContext, organizationSlug, filter))

  private[invitation] def listAction(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: InvitationFilters
  ): slick.dbio.DBIO[OrganizationInvitationListResult] =
    guard.resolve(authContext.id, organizationSlug, Vector.empty).flatMap {
      case None => slick.dbio.DBIO.successful[OrganizationInvitationListResult](OrganizationInvitationListResult.OperationNotPermitted)
      case Some(access) =>
        val scoped = filter.copy(organizationId = Some(access.organizationId))
        for {
          rows  <- invitationRepository.list(scoped)
          count <- invitationRepository.count(scoped)
        } yield OrganizationInvitationListResult.Ok(PaginatedResult(count, rows))
    }
}
object OrganizationInvitationListService {
  sealed trait OrganizationInvitationListResult
  object OrganizationInvitationListResult {
    final case class Ok(data: PaginatedResult[Invitation]) extends OrganizationInvitationListResult
    case object OperationNotPermitted                      extends OrganizationInvitationListResult
    case object OrganizationNotFound                       extends OrganizationInvitationListResult
  }
}
