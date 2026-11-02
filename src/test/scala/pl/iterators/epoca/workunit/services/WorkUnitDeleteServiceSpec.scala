package pl.iterators.epoca.workunit.services

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService.WorkUnitDeleteResult

class WorkUnitDeleteServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]  = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]   = allGenerators[OrganizationSlug]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]          = allGenerators[WorkUnit]
  lazy val rolesGenerator: AllGenerators[OrganizationUserRole] = allGenerators[OrganizationUserRole]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val workUnitRepository: WorkUnitRepository                 = vmock[WorkUnitRepository]

    val sut: WorkUnitDeleteService = wire[WorkUnitDeleteService]
  }
  "delete" should {
    "return OrganizationNotFound when user tries to delete work unit in non existing organization" in new TestCase {
      val orgSlug  = orgSlugGenerator.normal.generate
      val workUnit = workUnitGenerator.normal.generate

      val orgFilter = OrganizationFilters(slug = Some(orgSlug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization when user tries to delete work unit in non accessible organization" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate
      val organization = organizationGenerator.normal.generate

      val orgFilter     = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return WorkUnitNotFound when user tries to delete work unit that doesn't exist / was already deleted" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(rolesGenerator.normal.generate)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(None.asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.WorkUnitNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }
    "return WorkUnitNotFound when non-admin/non-owner user tries to delete work unit that wasn't created by him" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Manager)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.NoAccessToWorkUnit)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }
    "deletes work unit otherwise" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate.copy(userId = authUser.id, archivedAt = None)
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Employee)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenReturn(Some(workUnit).asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.Deleted)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.archivedAt.isDefined && unit.archivedAt.get == unit.modifiedAt
      })
    }
    "admin skips unnecessary interactions" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate.copy(userId = authUser.id, archivedAt = None)
      val organization = organizationGenerator.normal.generate

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenReturn(Some(workUnit).asDBIO)

      sut.delete(authAdmin, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.Deleted)

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.archivedAt.isDefined && unit.archivedAt.get == unit.modifiedAt && unit.userId != authAdmin.id
      })
    }
    "owner can delete other users work units" in new TestCase {
      val orgSlug      = orgSlugGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate.copy(archivedAt = None)
      val organization = organizationGenerator.normal.generate
      val roles        = Seq(OrganizationUserRole.Owner)
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val orgFilter      = OrganizationFilters(slug = Some(orgSlug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)
      when(workUnitRepository.update(any)).thenReturn(Some(workUnit).asDBIO)

      sut.delete(authUser, orgSlug, workUnit.id).asserting(_ shouldEqual WorkUnitDeleteResult.Deleted)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).find(workUnitFilter)
      verify(workUnitRepository).update(argThat { unit: WorkUnit =>
        unit.archivedAt.isDefined && unit.archivedAt.get == unit.modifiedAt
      })
    }
  }
}
