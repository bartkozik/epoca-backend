package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.services.OrganizationInvitationCreateService._
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber

class OrganizationInvitationCreateService(management: InvitationManagementService) {
  def create(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: OrganizationInvitationCreateRequest
  ): IO[OrganizationInvitationCreateResult] =
    management.createLegacy(authContext.id, organizationSlug, request).map {
      case InvitationManagementService.LegacyCreated(invitation) => OrganizationInvitationCreateResult.Created(invitation)
      case InvitationManagementService.NotFound                  => OrganizationInvitationCreateResult.OperationNotPermitted
      case InvitationManagementService.AlreadyMember             => OrganizationInvitationCreateResult.AlreadyMember
      case InvitationManagementService.InvitationAlreadyExists   => OrganizationInvitationCreateResult.InvitationAlreadyExists
      case InvitationManagementService.IdentityConflict          => OrganizationInvitationCreateResult.IdentityConflict
      case _: InvitationManagementService.InvalidRequest         => OrganizationInvitationCreateResult.InvalidRequest
      case other => throw new IllegalStateException(s"Unexpected legacy create result: ${other.getClass.getSimpleName}")
    }
}

object OrganizationInvitationCreateService {
  final case class OrganizationInvitationCreateRequest(
    email: Option[MailAddress],
    phoneNumber: Option[PhoneNumber],
    roles: Option[Seq[OrganizationUserRole]],
    expireTimestamp: Option[InvitationExpireAt])

  sealed trait OrganizationInvitationCreateResult
  object OrganizationInvitationCreateResult {
    final case class Created(invitation: Invitation) extends OrganizationInvitationCreateResult
    final case object InvalidRequest                 extends OrganizationInvitationCreateResult
    final case object InvitationAlreadyExists        extends OrganizationInvitationCreateResult
    final case object IdentityConflict               extends OrganizationInvitationCreateResult
    final case object AlreadyMember                  extends OrganizationInvitationCreateResult
    final case object OperationNotPermitted          extends OrganizationInvitationCreateResult
    final case object OrganizationNotFound           extends OrganizationInvitationCreateResult
  }

}
