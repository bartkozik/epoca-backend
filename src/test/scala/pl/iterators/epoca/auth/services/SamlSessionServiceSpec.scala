package pl.iterators.epoca.auth.services

import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext}
import pl.iterators.epoca.auth.repositories.AccountAuthenticationRepository
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationArchivedAt, OrganizationUserArchivedAt}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class SamlSessionServiceSpec extends ServiceSpec {
  trait TestCase extends BaseScope {
    val authentication: AccountAuthenticationRepository = vmock[AccountAuthenticationRepository]
    val organizations: OrganizationRepository           = vmock[OrganizationRepository]
    val memberships: OrganizationUserRepository         = vmock[OrganizationUserRepository]
    val sut                                             = new SamlSessionService(transactor, authentication, organizations, memberships)

    val organization: Organization = allGenerators[Organization].normal.generate.copy(archivedAt = None)
    val membership: OrganizationUser =
      allGenerators[OrganizationUser].normal.generate.copy(organizationId = organization.id, userId = authUser.id, archivedAt = None)
    val bound: BoundSaml     = BoundSaml(organization.id, 7L, "trusted-issuer", "bound-subject")
    val context: AuthContext = authUser.copy(accountAuthentication = Some(AccountAuthentication(Instant.now().minusSeconds(60), bound)))
    val memberFilter: OrganizationUserFilters   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(context.id))
    val organizationFilter: OrganizationFilters = OrganizationFilters(id = Some(organization.id))

    def stubTrustedBinding(): Unit = {
      when(authentication.activeHumanForShare(context.id)).thenReturn(true.asDBIO)
      when(authentication.currentSamlBindingForShare(context.id, bound)).thenReturn(true.asDBIO)
      ()
    }

    def verifyTrustedBinding(): Unit = {
      verify(authentication).activeHumanForShare(context.id)
      verify(authentication).currentSamlBindingForShare(context.id, bound)
      ()
    }
  }

  "currentOrganization" should {
    "resolve the current organization only for an active human with current trust and active membership" in new TestCase {
      stubTrustedBinding()
      when(memberships.find(memberFilter)).thenReturn(Some(membership).asDBIO)
      when(organizations.find(organizationFilter)).thenReturn(Some(organization).asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual Some(organization.slug))

      verifyTrustedBinding()
      verify(memberships).find(memberFilter)
      verify(organizations).find(organizationFilter)
    }

    "reject absent authentication proof before querying trust or membership" in new TestCase {
      sut.currentOrganization(context.copy(accountAuthentication = None)).asserting(_ shouldEqual None)

      verifyNoInteractions(authentication, memberships, organizations)
    }

    "reject future authentication proof before querying trust or membership" in new TestCase {
      val future = context.copy(accountAuthentication = Some(AccountAuthentication(Instant.now().plusSeconds(3600), bound)))

      sut.currentOrganization(future).asserting(_ shouldEqual None)

      verifyNoInteractions(authentication, memberships, organizations)
    }

    "reject archived or nonhuman accounts before checking bindings" in new TestCase {
      when(authentication.activeHumanForShare(context.id)).thenReturn(false.asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verify(authentication).activeHumanForShare(context.id)
      verifyNoInteractions(memberships, organizations)
    }

    "reject revoked bindings or changed trust epochs before checking membership" in new TestCase {
      when(authentication.activeHumanForShare(context.id)).thenReturn(true.asDBIO)
      when(authentication.currentSamlBindingForShare(context.id, bound)).thenReturn(false.asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verifyTrustedBinding()
      verifyNoInteractions(memberships, organizations)
    }

    "reject users who are not members of the bound organization" in new TestCase {
      stubTrustedBinding()
      when(memberships.find(memberFilter)).thenReturn(Option.empty[OrganizationUser].asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verifyTrustedBinding()
      verify(memberships).find(memberFilter)
      verifyNoInteractions(organizations)
    }

    "reject archived memberships even if a repository returns them" in new TestCase {
      stubTrustedBinding()
      when(memberships.find(memberFilter)).thenReturn(Some(membership.copy(archivedAt = Some(OrganizationUserArchivedAt(Instant.now())))).asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verifyTrustedBinding()
      verify(memberships).find(memberFilter)
      verifyNoInteractions(organizations)
    }

    "reject archived organizations even if a repository returns them" in new TestCase {
      stubTrustedBinding()
      when(memberships.find(memberFilter)).thenReturn(Some(membership).asDBIO)
      when(organizations.find(organizationFilter))
        .thenReturn(Some(organization.copy(archivedAt = Some(OrganizationArchivedAt(Instant.now())))).asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verifyTrustedBinding()
      verify(memberships).find(memberFilter)
      verify(organizations).find(organizationFilter)
    }

    "reject missing organizations" in new TestCase {
      stubTrustedBinding()
      when(memberships.find(memberFilter)).thenReturn(Some(membership).asDBIO)
      when(organizations.find(organizationFilter)).thenReturn(Option.empty[Organization].asDBIO)

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verifyTrustedBinding()
      verify(memberships).find(memberFilter)
      verify(organizations).find(organizationFilter)
    }

    "fail closed when current trust cannot be read" in new TestCase {
      when(authentication.activeHumanForShare(context.id)).thenReturn(slick.dbio.DBIO.failed(new IllegalStateException("database unavailable")))

      sut.currentOrganization(context).asserting(_ shouldEqual None)

      verify(authentication).activeHumanForShare(context.id)
      verifyNoInteractions(memberships, organizations)
    }
  }
}
