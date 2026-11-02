package pl.iterators.epoca.client.services

import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.ClientModifiedAt
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.services.ClientUpdateService.{ClientUpdateRequest, ClientUpdateResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class ClientUpdateServiceSpec extends ServiceSpec {
  private val pastModifiedAt = ClientModifiedAt(Instant.EPOCH)

  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]  = allGenerators[OrganizationSlug]
  lazy val clientGenerator: AllGenerators[Client]             = allGenerators[Client]
  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]

    val sut: ClientUpdateService = wire[ClientUpdateService]

    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)
    val orgSlug                    = orgSlugGenerator.normal.generate
    val client: Client = clientGenerator.normal.generate.copy(archivedAt = None, organizationId = organization.id, modifiedAt = pastModifiedAt)
    val request: ClientUpdateRequest = allGenerators[ClientUpdateRequest].maximal.generate
  }

  "update" should {
    "return OrganizationNotFound when admin tries to update client in non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authAdmin, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return NoAccessToOrganization when user not from organization tries to update client" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "employee can't update client" in new TestCase {
      val roles   = Seq(OrganizationUserRole.Employee)
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)

      sut.update(authUser, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.InsufficientRole)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
    }

    "return ClientNotFound when user with access to organization tries to update not existing client" in new TestCase {
      val roles   = Seq(OrganizationUserRole.Manager)
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.ClientNotFound)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id)))
    }

    "return ClientNotFound when admin tries to update not existing client" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authAdmin, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.ClientNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id)))
    }
    "return DuplicatedName if different client than target has name provided in request" in new TestCase {
      val roles   = Seq(OrganizationUserRole.Owner)
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)
      val client2 = clientGenerator.normal.generate

      val clientTargetFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id))
      val clientNameFilter   = ClientFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientTargetFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(clientNameFilter)).thenReturn(Some(client2).asDBIO)

      sut.update(authUser, orgSlug, client.id, request).asserting(_ shouldEqual ClientUpdateResult.DuplicatedName)

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientTargetFilter)
      verify(clientRepository).find(clientNameFilter)
    }

    "return Updated for user with access to organization with existing client" in new TestCase {
      val roles   = Seq(OrganizationUserRole.Owner)
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val clientTargetFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id))
      val clientNameFilter   = ClientFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientTargetFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(clientNameFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Client]).asDBIO
      }

      val result = sut.update(authUser, orgSlug, client.id, request)
      result.asserting(_ should beAnInstanceOf[ClientUpdateResult.Updated])

      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.name shouldEqual request.name.getOrElse(client.name))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.color shouldEqual request.color.getOrElse(client.color))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.logo shouldEqual request.logo.getOrElse(client.logo))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.`type` shouldEqual request.`type`.getOrElse(client.`type`))

      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.modifiedBy shouldEqual authUser.id)
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.archivedAt should beNone)
      result.asserting(
        _.asInstanceOf[ClientUpdateResult.Updated].client
          .copy(
            name = client.name,
            color = client.color,
            logo = client.logo,
            texture = client.texture,
            `type` = client.`type`,
            modifiedAt = client.modifiedAt,
            modifiedBy = client.modifiedBy
          ) shouldEqual client
      )

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientTargetFilter)
      verify(clientRepository).find(clientNameFilter)
      verify(clientRepository).update(any)
    }

    "return Updated for admin with existing client" in new TestCase {
      val clientTargetFilter = ClientFilters(organizationIds = Some(Seq(organization.id)), id = Some(client.id))
      val clientNameFilter   = ClientFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(clientRepository.find(clientTargetFilter)).thenReturn(Some(client).asDBIO)
      when(clientRepository.find(clientNameFilter)).thenReturn(None.asDBIO)
      when(clientRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Client]).asDBIO
      }

      val result = sut.update(authAdmin, orgSlug, client.id, request)
      result.asserting(_ should beAnInstanceOf[ClientUpdateResult.Updated])

      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.name shouldEqual request.name.getOrElse(client.name))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.color shouldEqual request.color.getOrElse(client.color))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.logo shouldEqual request.logo.getOrElse(client.logo))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.`type` shouldEqual request.`type`.getOrElse(client.`type`))
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.modifiedBy shouldEqual authAdmin.id)
      result.asserting(_.asInstanceOf[ClientUpdateResult.Updated].client.archivedAt should beNone)
      result.asserting(
        _.asInstanceOf[ClientUpdateResult.Updated].client
          .copy(
            name = client.name,
            color = client.color,
            logo = client.logo,
            texture = client.texture,
            `type` = client.`type`,
            modifiedAt = client.modifiedAt,
            modifiedBy = client.modifiedBy
          ) shouldEqual client
      )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(orgSlug)))
      verify(clientRepository).find(clientTargetFilter)
      verify(clientRepository).find(clientNameFilter)
      verify(clientRepository).update(any)
    }
  }
}
