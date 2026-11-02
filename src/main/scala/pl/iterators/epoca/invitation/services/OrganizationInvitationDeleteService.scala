package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.services.OrganizationInvitationDeleteService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug

class OrganizationInvitationDeleteService(management: InvitationManagementService) {
  def delete(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: InvitationId
  ): IO[OrganizationInvitationDeleteResult] =
    management.cancelLegacy(authContext.id, organizationSlug, id).map {
      case _: InvitationManagementService.Ok                => OrganizationInvitationDeleteResult.Deleted
      case InvitationManagementService.NotFound             => OrganizationInvitationDeleteResult.InvitationNotFound
      case InvitationManagementService.InvitationNotPending => OrganizationInvitationDeleteResult.InvitationNotPending
      case other => throw new IllegalStateException(s"Unexpected legacy cancel result: ${other.getClass.getSimpleName}")
    }
}

object OrganizationInvitationDeleteService {
  sealed trait OrganizationInvitationDeleteResult
  object OrganizationInvitationDeleteResult {
    final case object InvitationNotPending  extends OrganizationInvitationDeleteResult
    final case object Deleted               extends OrganizationInvitationDeleteResult
    final case object OperationNotPermitted extends OrganizationInvitationDeleteResult
    final case object InvitationNotFound    extends OrganizationInvitationDeleteResult
    final case object OrganizationNotFound  extends OrganizationInvitationDeleteResult
  }

}
