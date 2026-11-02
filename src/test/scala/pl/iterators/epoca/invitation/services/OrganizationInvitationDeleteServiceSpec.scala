package pl.iterators.epoca.invitation.services

import io.circe.Json
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.services.OrganizationInvitationDeleteService._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationInvitationDeleteServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val management: InvitationManagementService = vmock[InvitationManagementService]
    val sut                                     = new OrganizationInvitationDeleteService(management)
    val slug: OrganizationSlug                  = allGenerators[OrganizationSlug].normal.generate
    val id: InvitationId                        = allGenerators[InvitationId].normal.generate
  }
  "legacy delete adapter" should {
    "cancel through the shared service without a legacy revision field" in new TestCase {
      when(management.cancelLegacy(authUser.id, slug, id)).thenReturn(InvitationManagementService.Ok(Json.obj(), None).asIO)
      sut.delete(authUser, slug, id).asserting(_ mustEqual OrganizationInvitationDeleteResult.Deleted)
      verify(management).cancelLegacy(authUser.id, slug, id)
    }
    "preserve a current database authorization denial for an admin token" in new TestCase {
      when(management.cancelLegacy(authAdmin.id, slug, id)).thenReturn(InvitationManagementService.NotFound.asIO)
      sut.delete(authAdmin, slug, id).asserting(_ mustEqual OrganizationInvitationDeleteResult.InvitationNotFound)
      verify(management).cancelLegacy(authAdmin.id, slug, id)
    }
    "allow a superadmin when the shared service authorizes cancellation" in new TestCase {
      when(management.cancelLegacy(authAdmin.id, slug, id)).thenReturn(InvitationManagementService.Ok(Json.obj(), None).asIO)
      sut.delete(authAdmin, slug, id).asserting(_ mustEqual OrganizationInvitationDeleteResult.Deleted)
      verify(management).cancelLegacy(authAdmin.id, slug, id)
    }
    "preserve accepted and cancelled invitations" in new TestCase {
      when(management.cancelLegacy(authUser.id, slug, id)).thenReturn(InvitationManagementService.InvitationNotPending.asIO)
      sut.delete(authUser, slug, id).asserting(_ mustEqual OrganizationInvitationDeleteResult.InvitationNotPending)
      verify(management).cancelLegacy(authUser.id, slug, id)
    }
  }
}
