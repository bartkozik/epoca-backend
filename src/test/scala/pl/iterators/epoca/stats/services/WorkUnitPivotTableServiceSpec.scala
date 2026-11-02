package pl.iterators.epoca.stats.services

import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.stats.domain.StatsDomain.{StatsEntriesCount, WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotTableRow
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDuration, WorkUnitEntryType}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}

import java.time.Duration
import java.util.UUID

class WorkUnitPivotTableServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]     = allGenerators[Organization]
  lazy val keyGenerator: AllGenerators[WorkUnitPivotTableKey]     = allGenerators[WorkUnitPivotTableKey]
  lazy val valueGenerator: AllGenerators[WorkUnitPivotTableValue] = allGenerators[WorkUnitPivotTableValue]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]             = allGenerators[WorkUnit]

  private def worklog(duration: Duration = Duration.ofHours(1)): WorkUnit =
    workUnitGenerator.normal.generate.copy(duration = WorkUnitDuration(duration), entryType = WorkUnitEntryType.Worklog)

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val workUnitRepository: WorkUnitRepository                 = vmock[WorkUnitRepository]

    val sut: WorkUnitPivotTableService = wire[WorkUnitPivotTableService]
  }

  "buildPivotTable" should {
    "return KeysRequired if keys in request are empty" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val value        = valueGenerator.normal.generate
      val request      = WorkUnitPivotTableRequest(Seq(), Seq(value))

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.KeysRequired)
    }
    "return ValuesRequired if values in request are empty" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = keyGenerator.normal.generate
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq())

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.ValuesRequired)
    }
    "return OrganizationNotFound if organization doesn't exist" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = keyGenerator.normal.generate
      val value        = valueGenerator.normal.generate
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq(value))

      val orgFilter = OrganizationFilters(slug = Some(organization.slug))

      when(organizationRepository.find(orgFilter)).thenReturn(None.asDBIO)

      sut
        .buildPivotTable(authUser, organization.slug, request, WorkUnitFilters())
        .asserting(_ shouldEqual WorkUnitPivotTableResult.OrganizationNotFound)

      verify(organizationRepository).find(orgFilter)
    }
    "return NoAccessToOrganization if user isn't organization member" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = keyGenerator.normal.generate
      val value        = valueGenerator.normal.generate
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq(value))

      val orgFilter     = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(None.asDBIO)

      sut
        .buildPivotTable(authUser, organization.slug, request, WorkUnitFilters())
        .asserting(_ shouldEqual WorkUnitPivotTableResult.NoAccessToOrganization)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return InsufficientRole if user is organization member with bad roles" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = WorkUnitPivotTableKey.ProjectKey
      val value        = WorkUnitPivotTableValue.EntriesCount
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq(value))
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)

      val orgFilter     = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.InsufficientRole)

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
    }
    "return Ok with service data otherwise" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = WorkUnitPivotTableKey.ProjectKey
      val value        = WorkUnitPivotTableValue.EntriesCount
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq(value))
      val workUnit     = worklog()
      val clientId     = ClientId(UUID.randomUUID())
      val workUnits    = Seq(WorkUnitWithClientId(workUnit, clientId), WorkUnitWithClientId(workUnit, clientId))
      val row          = WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), projectId = Some(workUnit.projectId))
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(organizationId = Some(organization.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.listWithClientId(workUnitFilter)).thenReturn(workUnits.asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.Ok(Seq(row)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).listWithClientId(workUnitFilter)
    }
    "return Ok with service data otherwise with correct filter" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val key          = WorkUnitPivotTableKey.ProjectKey
      val value        = WorkUnitPivotTableValue.EntriesCount
      val request      = WorkUnitPivotTableRequest(Seq(key), Seq(value))
      val workUnit     = worklog()
      val clientId     = ClientId(UUID.randomUUID())
      val workUnits    = Seq(WorkUnitWithClientId(workUnit, clientId), WorkUnitWithClientId(workUnit, clientId))
      val row          = WorkUnitPivotTableRow(entriesCount = Some(StatsEntriesCount(2)), projectId = Some(workUnit.projectId))
      val orgUser      = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val filterBase     = allGenerators[WorkUnitFilters].maximal.generate
      val workUnitFilter = filterBase.copy(organizationId = Some(organization.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.listWithClientId(workUnitFilter)).thenReturn(workUnits.asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, filterBase).asserting(_ shouldEqual WorkUnitPivotTableResult.Ok(Seq(row)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).listWithClientId(workUnitFilter)
    }
    "return mixed Worklog and Note metrics using Worklogs for time" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request = WorkUnitPivotTableRequest(
        Seq(WorkUnitPivotTableKey.ProjectKey),
        Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg)
      )
      val worklog30 = worklog(Duration.ofMinutes(30))
      val worklog90 = worklog30.copy(duration = WorkUnitDuration(Duration.ofMinutes(90)))
      val note      = worklog30.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)
      val clientId  = ClientId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
      val workUnits = Seq(worklog30, worklog90, note).map(WorkUnitWithClientId(_, clientId))
      val row = WorkUnitPivotTableRow(
        projectId = Some(worklog30.projectId),
        entriesCount = Some(StatsEntriesCount(3)),
        durationSum = Some(Duration.ofHours(2)),
        durationAvg = Some(Duration.ofHours(1))
      )
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(organizationId = Some(organization.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.listWithClientId(workUnitFilter)).thenReturn(workUnits.asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.Ok(Seq(row)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).listWithClientId(workUnitFilter)
    }
    "return zero time metrics for an all-Note group" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request = WorkUnitPivotTableRequest(
        Seq(WorkUnitPivotTableKey.ProjectKey),
        Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg)
      )
      val note      = worklog().copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)
      val clientId  = ClientId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
      val workUnits = Seq(note, note).map(WorkUnitWithClientId(_, clientId))
      val row = WorkUnitPivotTableRow(
        projectId = Some(note.projectId),
        entriesCount = Some(StatsEntriesCount(2)),
        durationSum = Some(Duration.ZERO),
        durationAvg = Some(Duration.ZERO)
      )
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(organizationId = Some(organization.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.listWithClientId(workUnitFilter)).thenReturn(workUnits.asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting(_ shouldEqual WorkUnitPivotTableResult.Ok(Seq(row)))

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).listWithClientId(workUnitFilter)
    }
    "group rows by entry type" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val request = WorkUnitPivotTableRequest(
        Seq(WorkUnitPivotTableKey.EntryTypeKey),
        Seq(WorkUnitPivotTableValue.EntriesCount, WorkUnitPivotTableValue.DurationSum, WorkUnitPivotTableValue.DurationAvg)
      )
      val worklog30 = worklog(Duration.ofMinutes(30))
      val note      = worklog30.copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note)
      val clientId  = ClientId(UUID.fromString("00000000-0000-0000-0000-000000000103"))
      val workUnits = Seq(worklog30, note).map(WorkUnitWithClientId(_, clientId))
      val rows = Seq(
        WorkUnitPivotTableRow(
          entryType = Some(WorkUnitEntryType.Worklog),
          entriesCount = Some(StatsEntriesCount(1)),
          durationSum = Some(Duration.ofMinutes(30)),
          durationAvg = Some(Duration.ofMinutes(30))
        ),
        WorkUnitPivotTableRow(
          entryType = Some(WorkUnitEntryType.Note),
          entriesCount = Some(StatsEntriesCount(1)),
          durationSum = Some(Duration.ZERO),
          durationAvg = Some(Duration.ZERO)
        )
      )
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val orgFilter      = OrganizationFilters(slug = Some(organization.slug))
      val orgUserFilter  = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val workUnitFilter = WorkUnitFilters(organizationId = Some(organization.id))

      when(organizationRepository.find(orgFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(orgUserFilter)).thenReturn(Some(orgUser).asDBIO)
      when(workUnitRepository.listWithClientId(workUnitFilter)).thenReturn(workUnits.asDBIO)

      sut.buildPivotTable(authUser, organization.slug, request, WorkUnitFilters()).asserting {
        case WorkUnitPivotTableResult.Ok(data) => data should containTheSameElementsAs(rows)
        case result                            => result should beAnInstanceOf[WorkUnitPivotTableResult.Ok]
      }

      verify(organizationRepository).find(orgFilter)
      verify(organizationUserRepository).find(orgUserFilter)
      verify(workUnitRepository).listWithClientId(workUnitFilter)
    }

  }
}
