package pl.iterators.epoca.workunit.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.mockito.Mockito.verifyNoInteractions
import org.specs2.matcher.MatchResult
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{
  OrganizationSlug,
  OrganizationUserArchivedAt,
  OrganizationUserId,
  OrganizationUserRole
}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserArchivedAt, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.transactor.{FakeTransactor, IsolationLevel}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration, WorkUnitEntryType, WorkUnitState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import slick.dbio.DBIO

import java.time.{Clock, Duration, Instant, LocalDate, ZoneId}

class WorkUnitCreateServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]     = allGenerators[Organization]
  lazy val orgSlugGenerator: AllGenerators[OrganizationSlug]      = allGenerators[OrganizationSlug]
  lazy val requestGenerator: AllGenerators[WorkUnitCreateRequest] = allGenerators[WorkUnitCreateRequest]
  lazy val projectGenerator: AllGenerators[Project]               = allGenerators[Project]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]       = allGenerators[ProjectUser]
  lazy val userGenerator: AllGenerators[User]                     = allGenerators[User]
  lazy val workUnitGenerator: AllGenerators[WorkUnit]             = allGenerators[WorkUnit]
  lazy val workUnitDtoGenerator: AllGenerators[WorkUnitDto]       = allGenerators[WorkUnitDto]

  val fixedInstant: Instant           = Instant.parse("2026-07-29T10:00:00Z")
  val futureInstant: Instant          = fixedInstant.plusSeconds(24L * 60L * 60L)
  val businessZone: ZoneId            = ZoneId.of("Europe/Warsaw")
  val businessDate: LocalDate         = LocalDate.parse("2026-07-29")
  val today: WorkUnitDate             = WorkUnitDate(businessDate)
  val yesterday: WorkUnitDate         = WorkUnitDate(businessDate.minusDays(1L))
  val tomorrow: WorkUnitDate          = WorkUnitDate(businessDate.plusDays(1L))
  val neutral: WorkUnitDuration       = WorkUnitDuration(Duration.ZERO)
  val positive: WorkUnitDuration      = WorkUnitDuration(Duration.ofHours(1L))
  val exactlySecond: WorkUnitDuration = WorkUnitDuration(Duration.ofSeconds(1L))
  val overSecond: WorkUnitDuration    = WorkUnitDuration(Duration.ofMillis(1500L))

  trait TestCase extends BaseScope {
    override val transactor: WorkUnitCreateServiceSpec.RecordingTransactor = new WorkUnitCreateServiceSpec.RecordingTransactor()
    val workUnitRepository: WorkUnitRepository                             = vmock[WorkUnitRepository]
    val organizationRepository: OrganizationRepository                     = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository             = vmock[OrganizationUserRepository]
    val projectRepository: ProjectRepository                               = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository                       = vmock[ProjectUserRepository]
    val userRepository: UserRepository                                     = vmock[UserRepository]
    val workUnitDtoService: WorkUnitDtoService                             = vmock[WorkUnitDtoService]
    val workUnitBusinessClock: WorkUnitBusinessClock = WorkUnitBusinessClock(Clock.fixed(fixedInstant, businessZone), businessZone)

    val orgSlug: OrganizationSlug  = orgSlugGenerator.normal.generate
    val organization: Organization = organizationGenerator.normal.generate.copy(slug = orgSlug, archivedAt = None)
    val project: Project           = projectGenerator.normal.generate.copy(organizationId = organization.id, archivedAt = None, archivedBy = None)
    val targetUser: User           = activeUser(userGenerator.normal.generate.id)
    val dto: WorkUnitDto           = workUnitDtoGenerator.normal.generate

    val sut: WorkUnitCreateService = wire[WorkUnitCreateService]

    override def after: MatchResult[Any] = {
      val mocksIgnoringStubs = ignoreStubs(_allMocks.toList: _*).map(_.asInstanceOf[AnyRef])
      verifyNoMoreInteractions(mocksIgnoringStubs.toIndexedSeq: _*)
      1 shouldEqual 1
    }

    def activeUser(id: UserId): User =
      userGenerator.normal.generate.copy(id = id, archivedAt = None)

    def request(
      authContext: AuthContext,
      duration: Option[WorkUnitDuration] = Some(positive),
      date: WorkUnitDate = today,
      entryType: Option[WorkUnitEntryType] = Some(WorkUnitEntryType.Worklog),
      userId: Option[UserId] = None,
      isDraft: Boolean = false
    ): WorkUnitCreateRequest =
      requestGenerator.normal.generate.copy(
        projectId = project.id,
        date = date,
        duration = duration,
        isDraft = isDraft,
        entryType = entryType,
        userId = userId.orElse(Some(authContext.id))
      )

    def organizationFilter: OrganizationFilters = OrganizationFilters(slug = Some(orgSlug))
    def organizationUserFilter(userId: UserId): OrganizationUserFilters =
      OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
    def projectFilter: ProjectFilters =
      ProjectFilters(id = Some(project.id), organizationIds = Some(Seq(organization.id)))
    def projectUserFilter(userId: UserId): ProjectUserFilters =
      ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(userId))

    def membership(userId: UserId, role: OrganizationUserRole): OrganizationUser =
      OrganizationUser(OrganizationUserId.random, organization.id, userId, Seq(role), None)

    def projectMembership(userId: UserId): ProjectUser =
      projectUserGenerator.normal.generate.copy(projectId = project.id, userId = userId, archivedAt = None)

    def stubOrganizationAndActor(authContext: AuthContext, role: Option[OrganizationUserRole]): Option[OrganizationUser] = {
      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      role.map { actorMembership =>
        val value = membership(authContext.id, actorMembership)
        when(organizationUserRepository.find(organizationUserFilter(authContext.id))).thenReturn(Some(value).asDBIO)
        value
      }
    }

    def stubActiveTarget(
      authContext: AuthContext,
      actorMembership: Option[OrganizationUser],
      target: User = targetUser
    ): Unit = {
      when(userRepository.find(UserFilters(id = Some(target.id)))).thenReturn(Some(target).asDBIO)
      if (authContext.isSuperAdmin || target.id != authContext.id) {
        when(organizationUserRepository.find(organizationUserFilter(target.id)))
          .thenReturn(Some(membership(target.id, OrganizationUserRole.Employee)).asDBIO)
        ()
      } else if (actorMembership.isEmpty)
        throw new IllegalStateException("self target must reuse the active actor membership")
    }

    def stubProjectAndActorAccess(authContext: AuthContext, role: Option[OrganizationUserRole]): Unit = {
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      if (!authContext.isSuperAdmin && role.exists(r => r == OrganizationUserRole.Employee || r == OrganizationUserRole.External)) {
        when(projectUserRepository.find(projectUserFilter(authContext.id))).thenReturn(Some(projectMembership(authContext.id)).asDBIO)
        ()
      }
    }

    def stubSelfThroughProject(authContext: AuthContext, role: Option[OrganizationUserRole]): Unit = {
      val actorMembership = stubOrganizationAndActor(authContext, role)
      stubActiveTarget(authContext, actorMembership, activeUser(authContext.id))
      stubProjectAndActorAccess(authContext, role)
    }

    def stubForeignThroughProject(
      authContext: AuthContext,
      role: Option[OrganizationUserRole],
      entryType: WorkUnitEntryType,
      targetAssigned: Boolean = true
    ): Unit = {
      val actorMembership = stubOrganizationAndActor(authContext, role)
      stubActiveTarget(authContext, actorMembership)
      stubProjectAndActorAccess(authContext, role)
      if (entryType == WorkUnitEntryType.Worklog && targetAssigned) {
        when(projectUserRepository.find(projectUserFilter(targetUser.id))).thenReturn(Some(projectMembership(targetUser.id)).asDBIO)
        ()
      } else if (entryType == WorkUnitEntryType.Worklog) {
        when(projectUserRepository.find(projectUserFilter(targetUser.id))).thenReturn(None.asDBIO)
        ()
      }
    }

    def stubPersistence(): Unit = {
      when(workUnitRepository.insert(any)).thenAnswerArgAsDBIO()
      when(workUnitDtoService.prepareDto(any)).thenReturn(dto.asDBIO)
      ()
    }

    def verifyPersisted(
      expectedRequest: WorkUnitCreateRequest,
      expectedUserId: UserId,
      expectedEntryType: WorkUnitEntryType,
      expectedDuration: WorkUnitDuration,
      expectedState: WorkUnitState
    ): Unit = {
      val _ = verify(workUnitRepository).insert(argThat { (unit: WorkUnit) =>
        unit.userId == expectedUserId &&
        unit.organizationId == organization.id &&
        unit.projectId == expectedRequest.projectId &&
        unit.date == expectedRequest.date &&
        unit.duration == expectedDuration &&
        unit.entryType == expectedEntryType &&
        unit.description == expectedRequest.description &&
        unit.taskId == expectedRequest.taskId &&
        unit.state == expectedState &&
        unit.archivedAt.isEmpty
      })
      ()
    }
  }

  "create" should {
    "execute create action transactionally" in new TestCase {
      when(organizationRepository.find(organizationFilter)).thenReturn(None.asDBIO)
      val createRequest = request(authUser)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.OrganizationNotFound)

      transactor.executeCalls shouldEqual 0
      transactor.transactionalCalls shouldEqual 1
    }

    "expose the same create action for an enclosing idempotency transaction" in new TestCase {
      when(organizationRepository.find(organizationFilter)).thenReturn(None.asDBIO)
      val createRequest = request(authUser)

      transactor
        .execute(sut.createAction(authUser, orgSlug, createRequest))
        .asserting(_ shouldEqual WorkUnitCreateResult.OrganizationNotFound)

      transactor.executeCalls shouldEqual 1
      transactor.transactionalCalls shouldEqual 0
    }

    val selfRoleCases = Seq(
      ("owner", Some(OrganizationUserRole.Owner), false),
      ("manager", Some(OrganizationUserRole.Manager), false),
      ("employee", Some(OrganizationUserRole.Employee), false),
      ("external", Some(OrganizationUserRole.External), false),
      ("super-admin", None, true)
    )

    selfRoleCases.foreach { case (name, role, useSuperAdmin) =>
      s"allow active $name to create a self worklog under the existing project-access matrix" in new TestCase {
        val authContext   = if (useSuperAdmin) authAdmin else authUser
        val createRequest = request(authContext)
        stubSelfThroughProject(authContext, role)
        stubPersistence()

        sut.create(authContext, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

        verifyPersisted(createRequest, authContext.id, WorkUnitEntryType.Worklog, positive, WorkUnitState.Active)
        if (name == "owner")
          verify(workUnitRepository).insert(argThat { (unit: WorkUnit) =>
            unit.createdAt == unit.modifiedAt
          })
        if (useSuperAdmin)
          verify(organizationUserRepository).find(organizationUserFilter(authContext.id))
        else
          verify(organizationUserRepository, times(1)).find(organizationUserFilter(authContext.id))
      }
    }

    "preserve legacy missing entry type and user aliases as a self worklog" in new TestCase {
      val createRequest = request(authUser, entryType = None).copy(userId = None)
      stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))
      stubPersistence()

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

      verifyPersisted(createRequest, authUser.id, WorkUnitEntryType.Worklog, positive, WorkUnitState.Active)
    }

    Seq(false, true).foreach { isDraft =>
      val stateName = if (isDraft) "Draft" else "Active"

      Seq(
        "missing"            -> Option.empty[WorkUnitDuration],
        "zero"               -> Some(neutral),
        "negative"           -> Some(WorkUnitDuration(Duration.ofSeconds(-1L))),
        "positive subsecond" -> Some(WorkUnitDuration(Duration.ofMillis(999L)))
      ).foreach { case (caseName, testedDuration) =>
        s"reject $caseName $stateName worklog duration as NonPositiveDuration" in new TestCase {
          val createRequest = request(authUser, duration = testedDuration, isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.NonPositiveDuration)

          verifyNoInteractions(workUnitRepository, workUnitDtoService)
        }
      }

      Seq("exactly PT1S" -> exactlySecond, "positive over one second" -> overSecond).foreach { case (caseName, testedDuration) =>
        s"accept and preserve $caseName for a $stateName worklog" in new TestCase {
          val createRequest = request(authUser, duration = Some(testedDuration), isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))
          stubPersistence()

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

          verifyPersisted(
            createRequest,
            authUser.id,
            WorkUnitEntryType.Worklog,
            testedDuration,
            if (isDraft) WorkUnitState.Draft else WorkUnitState.Active
          )
        }
      }

      Seq("missing" -> Option.empty[WorkUnitDuration], "explicit PT0S" -> Some(neutral)).foreach { case (caseName, testedDuration) =>
        s"accept $caseName duration as neutral PT0S for a $stateName note" in new TestCase {
          val createRequest = request(authUser, duration = testedDuration, entryType = Some(WorkUnitEntryType.Note), isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))
          stubPersistence()

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

          verifyPersisted(createRequest, authUser.id, WorkUnitEntryType.Note, neutral, if (isDraft) WorkUnitState.Draft else WorkUnitState.Active)
        }
      }

      Seq(
        "positive"           -> WorkUnitDuration(Duration.ofSeconds(1L)),
        "negative"           -> WorkUnitDuration(Duration.ofSeconds(-1L)),
        "positive subsecond" -> WorkUnitDuration(Duration.ofMillis(500L)),
        "negative subsecond" -> WorkUnitDuration(Duration.ofMillis(-500L))
      ).foreach { case (caseName, testedDuration) =>
        s"reject a $caseName non-neutral duration for a $stateName note" in new TestCase {
          val createRequest = request(authUser, duration = Some(testedDuration), entryType = Some(WorkUnitEntryType.Note), isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.NonNeutralNoteDuration)

          verifyNoInteractions(workUnitRepository, workUnitDtoService)
        }
      }

      Seq("yesterday" -> yesterday, "today" -> today).foreach { case (caseName, testedDate) =>
        s"accept $caseName for a $stateName worklog" in new TestCase {
          val createRequest = request(authUser, date = testedDate, isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))
          stubPersistence()

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

          verifyPersisted(createRequest, authUser.id, WorkUnitEntryType.Worklog, positive, if (isDraft) WorkUnitState.Draft else WorkUnitState.Active)
        }
      }

      s"reject tomorrow for a $stateName worklog using the business clock" in new TestCase {
        val createRequest = request(authUser, date = tomorrow, isDraft = isDraft)
        stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))

        sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.FutureWorklogDate)

        verifyNoInteractions(workUnitRepository, workUnitDtoService)
      }

      Seq("yesterday" -> yesterday, "today" -> today, "future" -> tomorrow).foreach { case (caseName, testedDate) =>
        s"accept $caseName for a $stateName note" in new TestCase {
          val createRequest = request(authUser, duration = None, date = testedDate, entryType = Some(WorkUnitEntryType.Note), isDraft = isDraft)
          stubSelfThroughProject(authUser, Some(OrganizationUserRole.Owner))
          stubPersistence()

          sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

          verifyPersisted(createRequest, authUser.id, WorkUnitEntryType.Note, neutral, if (isDraft) WorkUnitState.Draft else WorkUnitState.Active)
        }
      }
    }

    Seq("owner" -> Some(OrganizationUserRole.Owner), "super-admin" -> Option.empty[OrganizationUserRole]).foreach { case (name, role) =>
      s"allow $name to create a worklog for an active assigned foreign target" in new TestCase {
        val authContext   = if (role.isEmpty) authAdmin else authUser
        val createRequest = request(authContext, userId = Some(targetUser.id))
        stubForeignThroughProject(authContext, role, WorkUnitEntryType.Worklog)
        stubPersistence()

        sut.create(authContext, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

        verifyPersisted(createRequest, targetUser.id, WorkUnitEntryType.Worklog, positive, WorkUnitState.Active)
        verify(projectUserRepository).find(projectUserFilter(targetUser.id))
      }
    }

    Seq(OrganizationUserRole.Manager, OrganizationUserRole.Employee, OrganizationUserRole.External).foreach { role =>
      s"deny a ${role.entryName} foreign target before target, project, and assignment lookups" in new TestCase {
        val createRequest = request(authUser, duration = Some(WorkUnitDuration(Duration.ofSeconds(-1L))), userId = Some(targetUser.id))
        stubOrganizationAndActor(authUser, Some(role))

        sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.NoAccessToTargetUser)

        verifyNoInteractions(userRepository, projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
        verify(organizationUserRepository, never).find(organizationUserFilter(targetUser.id))
      }
    }

    "return TargetUserNotFound for a missing active global target" in new TestCase {
      val createRequest   = request(authUser, duration = None, entryType = Some(WorkUnitEntryType.Note), userId = Some(targetUser.id))
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      actorMembership must beSome
      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotFound)

      verify(organizationUserRepository, never).find(organizationUserFilter(targetUser.id))
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "return TargetUserNotFound for an archived global target" in new TestCase {
      val createRequest   = request(authUser, userId = Some(targetUser.id))
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      actorMembership must beSome
      val archivedTarget = targetUser.copy(archivedAt = Some(UserArchivedAt(fixedInstant)))
      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(Some(archivedTarget).asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotFound)

      verify(organizationUserRepository, never).find(organizationUserFilter(targetUser.id))
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "return the same TargetUserNotFound without writing for a service-account target" in new TestCase {
      val createRequest  = request(authUser, userId = Some(targetUser.id))
      val serviceAccount = targetUser.copy(accountType = UserAccountType.ServiceAccount)
      stubForeignThroughProject(authUser, Some(OrganizationUserRole.Owner), WorkUnitEntryType.Worklog)
      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(Some(serviceAccount).asDBIO)
      stubPersistence()

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotFound)

      verify(organizationUserRepository, never).find(organizationUserFilter(targetUser.id))
      verifyNoInteractions(projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "return TargetUserNotFound for a missing target organization membership" in new TestCase {
      val createRequest   = request(authUser, userId = Some(targetUser.id))
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      actorMembership must beSome
      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(Some(targetUser).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter(targetUser.id))).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotFound)

      verifyNoInteractions(projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "return TargetUserNotFound when the repository excludes an archived target organization membership" in new TestCase {
      val createRequest   = request(authUser, userId = Some(targetUser.id))
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      actorMembership must beSome
      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(Some(targetUser).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter(targetUser.id))).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotFound)

      verifyNoInteractions(projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "treat a self actor membership archived in the future as active" in new TestCase {
      val createRequest = request(authUser)
      val futureMembership = membership(authUser.id, OrganizationUserRole.Owner)
        .copy(archivedAt = Some(OrganizationUserArchivedAt(futureInstant)))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter(authUser.id))).thenReturn(Some(futureMembership).asDBIO)
      when(userRepository.find(UserFilters(id = Some(authUser.id)))).thenReturn(Some(activeUser(authUser.id)).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      stubPersistence()

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

      verifyPersisted(createRequest, authUser.id, WorkUnitEntryType.Worklog, positive, WorkUnitState.Active)
    }

    "treat a foreign target membership archived in the future as active" in new TestCase {
      val createRequest   = request(authUser, userId = Some(targetUser.id))
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      actorMembership must beSome
      val futureMembership = membership(targetUser.id, OrganizationUserRole.Employee)
        .copy(archivedAt = Some(OrganizationUserArchivedAt(futureInstant)))

      when(userRepository.find(UserFilters(id = Some(targetUser.id)))).thenReturn(Some(targetUser).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter(targetUser.id))).thenReturn(Some(futureMembership).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(projectUserFilter(targetUser.id))).thenReturn(Some(projectMembership(targetUser.id)).asDBIO)
      stubPersistence()

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

      verifyPersisted(createRequest, targetUser.id, WorkUnitEntryType.Worklog, positive, WorkUnitState.Active)
    }

    "return TargetUserNotAssignedToProject for a foreign worklog target without active assignment" in new TestCase {
      val createRequest = request(authUser, duration = None, userId = Some(targetUser.id))
      stubForeignThroughProject(authUser, Some(OrganizationUserRole.Owner), WorkUnitEntryType.Worklog, targetAssigned = false)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.TargetUserNotAssignedToProject)

      verifyNoInteractions(workUnitRepository, workUnitDtoService)
    }

    "skip target project assignment for a foreign note" in new TestCase {
      val createRequest = request(authUser, duration = None, entryType = Some(WorkUnitEntryType.Note), userId = Some(targetUser.id))
      stubForeignThroughProject(authUser, Some(OrganizationUserRole.Owner), WorkUnitEntryType.Note)
      stubPersistence()

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.Created(dto))

      verifyNoInteractions(projectUserRepository)
      verifyPersisted(createRequest, targetUser.id, WorkUnitEntryType.Note, neutral, WorkUnitState.Active)
    }

    "return OrganizationNotFound before duration validation" in new TestCase {
      val createRequest = request(authUser, duration = None)
      when(organizationRepository.find(organizationFilter)).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.OrganizationNotFound)
    }

    "return NoAccessToOrganization before target and duration validation" in new TestCase {
      val createRequest = request(authUser, duration = None, userId = Some(targetUser.id))
      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter(authUser.id))).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.NoAccessToOrganization)

      verifyNoInteractions(userRepository, projectRepository, projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "return ProjectNotFound before duration validation" in new TestCase {
      val createRequest   = request(authUser, duration = None)
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Owner))
      stubActiveTarget(authUser, actorMembership, activeUser(authUser.id))
      when(projectRepository.find(projectFilter)).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.ProjectNotFound)

      verifyNoInteractions(projectUserRepository, workUnitRepository, workUnitDtoService)
    }

    "preserve the existing actor project-access denial for a self worklog" in new TestCase {
      val createRequest   = request(authUser)
      val actorMembership = stubOrganizationAndActor(authUser, Some(OrganizationUserRole.Employee))
      stubActiveTarget(authUser, actorMembership, activeUser(authUser.id))
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectUserRepository.find(projectUserFilter(authUser.id))).thenReturn(None.asDBIO)

      sut.create(authUser, orgSlug, createRequest).asserting(_ shouldEqual WorkUnitCreateResult.NoAccessToProject)

      verifyNoInteractions(workUnitRepository, workUnitDtoService)
    }
  }
}

object WorkUnitCreateServiceSpec {
  final class RecordingTransactor(implicit runtime: IORuntime) extends FakeTransactor {
    var executeCalls: Int       = 0
    var transactionalCalls: Int = 0

    override def execute[T](action: => DBIO[T]): IO[T] = {
      executeCalls += 1
      super.execute(action)
    }

    override def executeTransactionally[T](action: => DBIO[T]): IO[T] = {
      transactionalCalls += 1
      super.executeTransactionally(action)
    }

    override def executeTransactionally[T](level: IsolationLevel)(action: => DBIO[T]): IO[T] = {
      transactionalCalls += 1
      super.executeTransactionally(level)(action)
    }
  }
}
