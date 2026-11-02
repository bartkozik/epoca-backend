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
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyPreflightService.{CreatePreflightResult, UpdatePreflightResult}

class WorkUnitIdempotencyPreflightServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]  = allGenerators[OrganizationSlug]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]         = allGenerators[WorkUnit]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val workUnitRepository: WorkUnitRepository                 = vmock[WorkUnitRepository]

    val sut: WorkUnitIdempotencyPreflightService = wire[WorkUnitIdempotencyPreflightService]
  }

  "create preflight" should {
    "stop with OrganizationNotFound before checking membership" in new TestCase {
      val slug   = orgSlugGenerator.normal.generate
      val filter = OrganizationFilters(slug = Some(slug))
      when(organizationRepository.find(filter)).thenReturn(None.asDBIO)

      transactor.execute(sut.preflightCreate(authUser, slug)).asserting(_ shouldEqual CreatePreflightResult.OrganizationNotFound)

      verify(organizationRepository).find(filter)
    }

    "deny a non-member without checking mutable work-unit validations" in new TestCase {
      val slug         = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgFilter    = OrganizationFilters(slug = Some(slug))
      val memberFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(None.asDBIO)

      transactor.execute(sut.preflightCreate(authUser, slug)).asserting(_ shouldEqual CreatePreflightResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
    }

    "allow a current organization member" in new TestCase {
      val slug         = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val member       = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val orgFilter    = OrganizationFilters(slug = Some(slug))
      val memberFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(Some(member).asDBIO)

      transactor.execute(sut.preflightCreate(authUser, slug)).asserting(_ shouldEqual CreatePreflightResult.Allowed(organization.id))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
    }

    "allow a super-admin without a membership lookup" in new TestCase {
      val slug         = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val orgFilter    = OrganizationFilters(slug = Some(slug))
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)

      transactor.execute(sut.preflightCreate(authAdmin, slug)).asserting(_ shouldEqual CreatePreflightResult.Allowed(organization.id))

      verify(organizationRepository).find(orgFilter)
    }
  }

  "update preflight" should {
    "stop after a missing organization" in new TestCase {
      val slug     = orgSlugGenerator.normal.generate
      val workUnit = workUnitGenerator.normal.generate
      val filter   = OrganizationFilters(slug = Some(slug))
      when(organizationRepository.find(filter)).thenReturn(None.asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.OrganizationNotFound)

      verify(organizationRepository).find(filter)
    }

    "stop after a missing current organization membership" in new TestCase {
      val slug         = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate
      val orgFilter    = OrganizationFilters(slug = Some(slug))
      val memberFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(None.asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
    }

    "read the target without a row lock and report a missing work unit" in new TestCase {
      val slug           = orgSlugGenerator.normal.generate
      val organization   = organizationGenerator.normal.generate
      val workUnit       = workUnitGenerator.normal.generate
      val member         = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val orgFilter      = OrganizationFilters(slug = Some(slug))
      val memberFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = false)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(Some(member).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(None.asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.WorkUnitNotFound)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }

    "allow the work-unit owner" in new TestCase {
      val slug           = orgSlugGenerator.normal.generate
      val organization   = organizationGenerator.normal.generate
      val workUnit       = workUnitGenerator.normal.generate.copy(userId = authUser.id, organizationId = organization.id)
      val member         = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val orgFilter      = OrganizationFilters(slug = Some(slug))
      val memberFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = false)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(Some(member).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.Allowed(organization.id))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }

    "allow a manager to target another user's work unit" in new TestCase {
      val slug           = orgSlugGenerator.normal.generate
      val organization   = organizationGenerator.normal.generate
      val workUnit       = workUnitGenerator.normal.generate.copy(organizationId = organization.id, userId = authAdmin.id)
      val member         = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Manager), None)
      val orgFilter      = OrganizationFilters(slug = Some(slug))
      val memberFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = false)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(Some(member).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.Allowed(organization.id))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }

    "deny an unrelated employee" in new TestCase {
      val slug           = orgSlugGenerator.normal.generate
      val organization   = organizationGenerator.normal.generate
      val workUnit       = workUnitGenerator.normal.generate.copy(organizationId = organization.id, userId = authAdmin.id)
      val member         = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)
      val orgFilter      = OrganizationFilters(slug = Some(slug))
      val memberFilter   = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = false)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(memberFilter)).thenReturn(Some(member).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      transactor
        .execute(sut.preflightUpdate(authUser, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.NoAccessToWorkUnit)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(memberFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }

    "allow a super-admin without a membership lookup" in new TestCase {
      val slug         = orgSlugGenerator.normal.generate
      val organization = organizationGenerator.normal.generate
      val workUnit     = workUnitGenerator.normal.generate.copy(organizationId = organization.id)
      val orgFilter    = OrganizationFilters(slug = Some(slug))
      val workUnitFilter =
        WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = false)
      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(workUnitRepository.find(workUnitFilter)).thenReturn(Some(workUnit).asDBIO)

      transactor
        .execute(sut.preflightUpdate(authAdmin, slug, workUnit.id))
        .asserting(_ shouldEqual UpdatePreflightResult.Allowed(organization.id))

      verify(organizationRepository).find(orgFilter)
      verify(workUnitRepository).find(workUnitFilter)
    }
  }
}
