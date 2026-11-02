package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.services.OrganizationInvitationCreateService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationInvitationCreateServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val management: InvitationManagementService      = vmock[InvitationManagementService]
    val sut                                          = new OrganizationInvitationCreateService(management)
    val slug: OrganizationSlug                       = allGenerators[OrganizationSlug].normal.generate
    val request: OrganizationInvitationCreateRequest = allGenerators[OrganizationInvitationCreateRequest].normal.generate
  }
  "legacy create adapter" should {
    Seq(
      InvitationManagementService.AlreadyMember           -> OrganizationInvitationCreateResult.AlreadyMember,
      InvitationManagementService.InvitationAlreadyExists -> OrganizationInvitationCreateResult.InvitationAlreadyExists,
      InvitationManagementService.IdentityConflict        -> OrganizationInvitationCreateResult.IdentityConflict,
      InvitationManagementService.InvalidRequest("bad")   -> OrganizationInvitationCreateResult.InvalidRequest
    ).foreach { case (result, expected) =>
      s"map $result" in new TestCase {
        when(management.createLegacy(authUser.id, slug, request)).thenReturn(result.asIO)
        sut.create(authUser, slug, request).asserting(_ mustEqual expected)
        verify(management).createLegacy(authUser.id, slug, request)
      }
    }
    "preserve the legacy response" in new TestCase {
      val invitation = allGenerators[Invitation].normal.generate
      when(management.createLegacy(authUser.id, slug, request)).thenReturn(InvitationManagementService.LegacyCreated(invitation).asIO)
      sut.create(authUser, slug, request).asserting(_ mustEqual OrganizationInvitationCreateResult.Created(invitation))
      verify(management).createLegacy(authUser.id, slug, request)
    }
    "delegate global admins to the same current database authorization" in new TestCase {
      when(management.createLegacy(authAdmin.id, slug, request)).thenReturn(InvitationManagementService.NotFound.asIO)
      sut.create(authAdmin, slug, request).asserting(_ mustEqual OrganizationInvitationCreateResult.OperationNotPermitted)
      verify(management).createLegacy(authAdmin.id, slug, request)
    }
  }
}
