package pl.iterators.epoca.oauth.adapters

import io.circe.Json
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.invocation.InvocationOnMock
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.oauth.adapters.OAuthWorkUnitWriteAdapter.OAuthWorkUnitCreateRequest
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDescription, WorkUnitDuration, WorkUnitEntryType, WorkUnitState}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyClaimResult.{Claimed, Existing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.{Completed, Processing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.Response
import pl.iterators.epoca.workunit.idempotency.{NewWorkUnitIdempotencyClaim, WorkUnitIdempotencyScope, WorkUnitWriteOutcome}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.{WorkUnitIdempotencyRepository, WorkUnitRepository}
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyService.WorkUnitIdempotencyConfig
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest
import pl.iterators.epoca.workunit.services.{
  WorkUnitBusinessClock,
  WorkUnitCreateService,
  WorkUnitDtoService,
  WorkUnitIdempotencyPreflightService,
  WorkUnitIdempotencyService,
  WorkUnitUpdateService,
  WorkUnitWriteOutcomeMapper
}

import java.time.{Clock, Duration, Instant, LocalDate, ZoneId}
import java.util.UUID

class OAuthWorkUnitWriteAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator = allGenerators[Organization].normal
  private lazy val projectGenerator      = allGenerators[Project].normal
  private lazy val projectUserGenerator  = allGenerators[ProjectUser].normal
  private lazy val userGenerator         = allGenerators[User].normal
  private lazy val workUnitGenerator     = allGenerators[WorkUnit].normal
  private lazy val workUnitDtoGenerator  = allGenerators[WorkUnitDtoService.WorkUnitDto].normal

  private val fixedInstant = Instant.parse("2026-08-13T10:00:00Z")
  private val businessZone = ZoneId.of("Europe/Warsaw")
  private val date         = WorkUnitDate(LocalDate.parse("2026-08-13"))
  private val duration     = WorkUnitDuration(Duration.ofMinutes(45L))
  private val correlation  = "ct-520-oauth-write"

  trait TestCase extends FixedTimeBaseScope {
    val organizationRepository: OrganizationRepository                   = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository           = vmock[OrganizationUserRepository]
    val workUnitRepository: WorkUnitRepository                           = vmock[WorkUnitRepository]
    val projectRepository: ProjectRepository                             = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository                     = vmock[ProjectUserRepository]
    val userRepository: UserRepository                                   = vmock[UserRepository]
    val workUnitDtoService: WorkUnitDtoService                           = vmock[WorkUnitDtoService]
    val idempotencyPreflightService: WorkUnitIdempotencyPreflightService = vmock[WorkUnitIdempotencyPreflightService]
    val idempotencyRepository: WorkUnitIdempotencyRepository             = vmock[WorkUnitIdempotencyRepository]
    val workUnitBusinessClock: WorkUnitBusinessClock =
      WorkUnitBusinessClock(Clock.fixed(fixedInstant, businessZone), businessZone)
    val outcomeMapper = new WorkUnitWriteOutcomeMapper()

    val createService = new WorkUnitCreateService(
      transactor,
      organizationRepository,
      organizationUserRepository,
      workUnitRepository,
      projectRepository,
      projectUserRepository,
      workUnitDtoService,
      userRepository,
      workUnitBusinessClock
    )
    val updateService = new WorkUnitUpdateService(
      transactor,
      organizationRepository,
      organizationUserRepository,
      workUnitRepository,
      projectRepository,
      projectUserRepository,
      userRepository,
      workUnitDtoService,
      workUnitBusinessClock
    )
    val idempotencyService =
      new WorkUnitIdempotencyService(transactor, idempotencyPreflightService, idempotencyRepository, outcomeMapper, WorkUnitIdempotencyConfig())(
        ioRuntime,
        fixedTimeDBIOClock
      )
    val sut = new OAuthWorkUnitWriteAdapter(transactor, organizationRepository, createService, updateService, outcomeMapper, idempotencyService)

    val organization: Organization = organizationGenerator.generate.copy(archivedAt = None)
    val p1: Project                = projectGenerator.generate.copy(organizationId = organization.id, archivedAt = None, archivedBy = None)
    val p2: Project                = projectGenerator.generate.copy(organizationId = organization.id, archivedAt = None, archivedBy = None)
    val human: User                = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
    val service: User              = userGenerator.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
    val policy: OAuthAccessPolicy  = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))
    val actorId: UserId            = userGenerator.generate.id

    def request(project: Project = p1, target: User = human): OAuthWorkUnitCreateRequest =
      OAuthWorkUnitCreateRequest(target.id, project.id, date, duration, WorkUnitDescription("OAuth worklog"), taskId = None)

    def updateRequest(projectId: Option[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId] = None): WorkUnitUpdateRequest =
      WorkUnitUpdateRequest(projectId, Some(duration), Some(WorkUnitDescription("Updated by OAuth")), None, Some(date), shouldFinalize = false)

    def organizationFilter: OrganizationFilters         = OrganizationFilters(id = Some(organization.id), slug = Some(organization.slug))
    def projectFilter(project: Project): ProjectFilters = ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)))
    def membershipFilter(userId: UserId): OrganizationUserFilters =
      OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
    def assignmentFilter(project: Project, userId: UserId): ProjectUserFilters =
      ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(userId))

    def stubOrganization(): Unit = {
      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      ()
    }

    def stubHumanTarget(project: Project = p1): Unit = {
      when(userRepository.find(UserFilters(id = Some(human.id)))).thenReturn(Some(human).asDBIO)
      when(organizationUserRepository.find(membershipFilter(human.id)))
        .thenReturn(Some(OrganizationUser(OrganizationUserId.random, organization.id, human.id, Seq(OrganizationUserRole.Employee), None)).asDBIO)
      when(projectRepository.find(projectFilter(project))).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(assignmentFilter(project, human.id)))
        .thenReturn(Some(projectUserGenerator.generate.copy(projectId = project.id, userId = human.id, archivedAt = None)).asDBIO)
      ()
    }

    def status(result: pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult): Int =
      result.asInstanceOf[Response].outcome.status

    def stubCachedOutcome(outcome: WorkUnitWriteOutcome): Unit = {
      stubOrganization()
      when(idempotencyRepository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val claim = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        Existing(
          Completed(
            claim.claimId,
            claim.scope,
            claim.operation,
            claim.fingerprintVersion,
            claim.canonicalRequest,
            claim.fingerprint,
            outcome,
            claim.createdAt,
            claim.createdAt.plusSeconds(86400)
          )
        ).asDBIO
      }
      ()
    }

    def correctedCachedOutcome(unit: WorkUnit): WorkUnitWriteOutcome =
      WorkUnitWriteOutcome(
        200,
        Json.obj(
          "id"            -> Json.fromString(unit.id.toString),
          "projectClient" -> Json.obj("projectId" -> Json.fromString(p1.id.toString)),
          "effectiveProjectClient" -> Json.obj(
            "projectId"   -> Json.fromString(p2.id.toString),
            "projectName" -> Json.fromString("Hidden corrected project"),
            "clientName"  -> Json.fromString("Hidden corrected client")
          ),
          "effectiveTaskId" -> Json.fromString("HIDDEN-567")
        ),
        Some(unit.id)
      )

    override def after: MatchResult[Any] = {
      val mocksIgnoringStubs = ignoreStubs(_allMocks.toList: _*).map(_.asInstanceOf[AnyRef])
      verifyNoMoreInteractions(mocksIgnoringStubs.toIndexedSeq: _*)
      1 shouldEqual 1
    }
  }

  "OAuthWorkUnitWriteAdapter" should {
    "authorize cached source and effective values against the current policy without rerunning a patch" in new TestCase {
      val unit          = workUnitGenerator.generate.copy(organizationId = organization.id, projectId = p1.id)
      val cached        = correctedCachedOutcome(unit)
      val key           = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee5671"))
      val both          = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id, p2.id)))
      val effectiveOnly = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p2.id)))
      stubCachedOutcome(cached)

      (for {
        sourceOnlyResult    <- sut.update(policy, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
        effectiveOnlyResult <- sut.update(effectiveOnly, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
        restoredResult      <- sut.update(both, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
      } yield (sourceOnlyResult, effectiveOnlyResult, restoredResult)).asserting { case (sourceOnlyResult, effectiveOnlyResult, restoredResult) =>
        verify(organizationRepository, times(3)).find(organizationFilter)
        verify(idempotencyRepository, times(3)).claim(any)
        verify(idempotencyRepository, never).complete(any, any, any)
        verifyNoInteractions(workUnitRepository, workUnitDtoService, userRepository, projectRepository)
        (status(sourceOnlyResult) shouldEqual 404) and
          (status(effectiveOnlyResult) shouldEqual 404) and
          (sourceOnlyResult.asInstanceOf[Response].replayed should beFalse) and
          (sourceOnlyResult.asInstanceOf[Response].outcome.body.noSpaces must not(contain("HIDDEN-567"))) and
          (restoredResult shouldEqual Response(cached, replayed = true))
      }
    }

    "not authorize a historical cached target using a different currently allowed override" in new TestCase {
      val currentTarget = projectGenerator.generate.copy(organizationId = organization.id)
      val unit = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        overrideState = WorkUnitOverrideState(projectId = Some(currentTarget.id))
      )
      val cached        = correctedCachedOutcome(unit)
      val currentPolicy = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id, currentTarget.id)))
      val key           = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee5672"))
      stubCachedOutcome(cached)
      when(workUnitRepository.find(any)).thenReturn(Some(unit).asDBIO)

      sut.update(currentPolicy, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation).asserting { result =>
        verify(organizationRepository).find(organizationFilter)
        verify(idempotencyRepository).claim(any)
        verifyNoInteractions(workUnitRepository, workUnitDtoService)
        (status(result) shouldEqual 404) and
          (result.asInstanceOf[Response].outcome.body.noSpaces must not(contain(p2.id.toString)))
      }
    }

    "fail closed on incomplete cached project scope while preserving unrestricted exact replay" in new TestCase {
      val unit        = workUnitGenerator.generate.copy(organizationId = organization.id, projectId = p1.id)
      val cached      = correctedCachedOutcome(unit).copy(body = Json.obj("projectClient" -> Json.Null))
      val key         = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee5673"))
      val allProjects = OAuthAccessPolicy(organization.id, OAuthProjectAccess.AllProjects)
      stubCachedOutcome(cached)

      (for {
        denied       <- sut.update(policy, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
        unrestricted <- sut.update(allProjects, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
      } yield (denied, unrestricted)).asserting { case (denied, unrestricted) =>
        verify(organizationRepository, times(2)).find(organizationFilter)
        verify(idempotencyRepository, times(2)).claim(any)
        verifyNoInteractions(workUnitRepository, workUnitDtoService)
        (status(denied) shouldEqual 404) and
          (unrestricted shouldEqual Response(cached, replayed = true))
      }
    }

    "preserve exact legacy source-only cached responses across the V26 cutover" in new TestCase {
      val unit     = workUnitGenerator.generate.copy(organizationId = organization.id, projectId = p1.id)
      val original = correctedCachedOutcome(unit)
      val cached   = original.copy(body = original.body.mapObject(_.remove("effectiveProjectClient").remove("effectiveTaskId")))
      val key      = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee5674"))
      val outside  = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p2.id)))
      stubCachedOutcome(cached)

      (for {
        allowed <- sut.update(policy, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
        denied  <- sut.update(outside, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation)
      } yield (allowed, denied)).asserting { case (allowed, denied) =>
        verify(organizationRepository, times(2)).find(organizationFilter)
        verify(idempotencyRepository, times(2)).claim(any)
        verifyNoInteractions(workUnitRepository, workUnitDtoService)
        (allowed shouldEqual Response(cached, replayed = true)) and (status(denied) shouldEqual 404)
      }
    }

    "not mistake a new response missing its effective project for a legacy source-only cache" in new TestCase {
      val unit     = workUnitGenerator.generate.copy(organizationId = organization.id, projectId = p1.id)
      val original = correctedCachedOutcome(unit)
      val cached =
        original.copy(body = original.body.mapObject(_.remove("effectiveProjectClient").remove("effectiveTaskId").add("revision", Json.fromInt(1))))
      val key = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee5675"))
      stubCachedOutcome(cached)

      sut.update(policy, actorId, organization.slug, unit.id, updateRequest(), Some(key), correlation).asserting { result =>
        verify(organizationRepository).find(organizationFilter)
        verify(idempotencyRepository).claim(any)
        verifyNoInteractions(workUnitRepository, workUnitDtoService)
        status(result) shouldEqual 404
      }
    }

    "create a work unit for an explicit human assigned to an allowed project" in new TestCase {
      val dto = workUnitDtoGenerator.generate.copy(userId = human.id, duration = duration)
      stubOrganization()
      stubHumanTarget()
      when(workUnitRepository.insert(any)).thenAnswerArgAsDBIO()
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

      sut.create(policy, actorId, organization.slug, request(), idempotencyKey = None, correlation).asserting { result =>
        val response = result.asInstanceOf[Response]
        val inserted = ArgumentCaptor.forClass(classOf[WorkUnit])
        verify(workUnitRepository).insert(inserted.capture)

        (response.outcome.status shouldEqual 201) and
          (response.replayed should beFalse) and
          (response.outcome.body.hcursor.get[String]("id") shouldEqual Right(dto.id.toString)) and
          (inserted.getValue.userId shouldEqual human.id) and
          (inserted.getValue.projectId shouldEqual p1.id)
      }
    }

    "return 404 without partial writes for a create targeting a project outside policy" in new TestCase {
      stubOrganization()

      sut.create(policy, actorId, organization.slug, request(project = p2), None, correlation).asserting(status(_) shouldEqual 404)

      verifyNoInteractions(
        organizationUserRepository,
        projectRepository,
        projectUserRepository,
        userRepository,
        workUnitRepository,
        workUnitDtoService
      )
    }

    "return 404 without partial writes for a cross-organization slug" in new TestCase {
      when(organizationRepository.find(organizationFilter)).thenReturn(None.asDBIO)

      sut.create(policy, actorId, organization.slug, request(), None, correlation).asserting(status(_) shouldEqual 404)

      verifyNoInteractions(
        organizationUserRepository,
        projectRepository,
        projectUserRepository,
        userRepository,
        workUnitRepository,
        workUnitDtoService
      )
    }

    "reject a service-account target as not found without writing" in new TestCase {
      stubOrganization()
      when(userRepository.find(UserFilters(id = Some(service.id)))).thenReturn(Some(service).asDBIO)

      sut.create(policy, actorId, organization.slug, request(target = service), None, correlation).asserting(status(_) shouldEqual 404)

      verifyNoInteractions(organizationUserRepository, projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "scope the existing idempotency contract to the OAuth service actor and explicit target" in new TestCase {
      val dto   = workUnitDtoGenerator.generate.copy(userId = human.id, duration = duration)
      val key   = WorkUnitIdempotencyKey(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
      var claim = Option.empty[NewWorkUnitIdempotencyClaim]
      stubOrganization()
      stubHumanTarget()
      when(workUnitRepository.insert(any)).thenAnswerArgAsDBIO()
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)
      when(idempotencyRepository.claim(any)).thenAnswer { (invocation: InvocationOnMock) =>
        val captured = invocation.getArgument[NewWorkUnitIdempotencyClaim](0)
        claim = Some(captured)
        Claimed(
          Processing(
            captured.claimId,
            captured.scope,
            captured.operation,
            captured.fingerprintVersion,
            captured.canonicalRequest,
            captured.fingerprint,
            captured.createdAt
          )
        ).asDBIO
      }
      when(idempotencyRepository.complete(any, any, any)).thenReturn(1.asDBIO)

      sut.create(policy, actorId, organization.slug, request(), Some(key), correlation).asserting { result =>
        (status(result) shouldEqual 201) and
          (claim.map(_.scope) shouldEqual Some(WorkUnitIdempotencyScope(organization.id, actorId, key))) and
          (claim.map(_.canonicalRequest.hcursor.downField("payload").get[String]("targetUserId")) shouldEqual Some(Right(human.id.toString)))
      }

      verify(organizationRepository, times(2)).find(organizationFilter)
      verify(idempotencyRepository).claim(any)
      verify(idempotencyRepository).complete(any, any, any)
    }

    "update an allowed human work unit without changing its owner" in new TestCase {
      val workUnit = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        userId = human.id,
        entryType = WorkUnitEntryType.Worklog,
        date = date,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      val dto = workUnitDtoGenerator.generate.copy(id = workUnit.id, userId = human.id, duration = duration)
      stubOrganization()
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)
      when(userRepository.find(UserFilters(id = Some(human.id)))).thenReturn(Some(human).asDBIO)
      when(workUnitRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArgument[WorkUnit](0)).asDBIO
      }
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)

      sut.update(policy, actorId, organization.slug, workUnit.id, updateRequest(), None, correlation).asserting(status(_) shouldEqual 200)

      val updated = ArgumentCaptor.forClass(classOf[WorkUnit])
      verify(workUnitRepository).update(updated.capture)
      updated.getValue.userId shouldEqual human.id
    }

    "return 404 without partial writes when the current work unit is outside policy" in new TestCase {
      val workUnit = workUnitGenerator.generate.copy(organizationId = organization.id, projectId = p2.id, userId = human.id)
      stubOrganization()
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)

      sut.update(policy, actorId, organization.slug, workUnit.id, updateRequest(), None, correlation).asserting(status(_) shouldEqual 404)

      verify(workUnitRepository, never).update(any)
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitDtoService)
    }

    "return 404 without partial writes when a patch moves an allowed work unit outside policy" in new TestCase {
      val workUnit = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        userId = human.id,
        entryType = WorkUnitEntryType.Worklog,
        date = date,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      stubOrganization()
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)
      when(userRepository.find(UserFilters(id = Some(human.id)))).thenReturn(Some(human).asDBIO)

      sut.update(policy, actorId, organization.slug, workUnit.id, updateRequest(Some(p2.id)), None, correlation).asserting(status(_) shouldEqual 404)

      verify(projectRepository).lockTableForReference()
      verify(workUnitRepository, never).update(any)
      verifyNoInteractions(projectUserRepository, workUnitDtoService)
    }

    "return 404 before payload validation when AllProjects targets a cross-organization project" in new TestCase {
      val workUnit = workUnitGenerator.generate.copy(
        organizationId = organization.id,
        projectId = p1.id,
        userId = human.id,
        entryType = WorkUnitEntryType.Worklog,
        date = date,
        state = WorkUnitState.Active,
        archivedAt = None
      )
      val foreignProject = projectGenerator.generate.copy(organizationId = organizationGenerator.generate.id, archivedAt = None)
      val allProjects    = OAuthAccessPolicy(organization.id, OAuthProjectAccess.AllProjects)
      val invalidRequest = updateRequest(Some(foreignProject.id)).copy(duration = Some(WorkUnitDuration(Duration.ZERO)))
      val foreignFilter  = ProjectFilters(id = Some(foreignProject.id), organizationIds = Some(Seq(organization.id)))
      stubOrganization()
      when(projectRepository.lockTableForReference()).thenReturn(().asDBIO)
      when(workUnitRepository.find(WorkUnitFilters(id = Some(workUnit.id), organizationId = Some(organization.id), forUpdate = true)))
        .thenReturn(Some(workUnit).asDBIO)
      when(userRepository.find(UserFilters(id = Some(human.id)))).thenReturn(Some(human).asDBIO)
      when(projectRepository.find(foreignFilter)).thenReturn(None.asDBIO)

      sut.update(allProjects, actorId, organization.slug, workUnit.id, invalidRequest, None, correlation).asserting(status(_) shouldEqual 404)

      verify(projectRepository).find(foreignFilter)
      verify(workUnitRepository, never).update(any)
      verifyNoInteractions(projectUserRepository, workUnitDtoService)
    }
  }
}
