package pl.iterators.epoca.workunit.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitOverridePolicy._

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID

class WorkUnitOverridePolicySpec extends SpecificationLike {
  private val actorId         = userId(1)
  private val authorId        = userId(2)
  private val organizationId  = orgId(1)
  private val otherOrgId      = orgId(2)
  private val sourceProjectId = projectId(30)
  private val targetProjectId = projectId(10)
  private val otherProjectId  = projectId(20)
  private val workUnitId      = WorkUnitId(uuid(1))
  private val now             = Instant.parse("2026-08-05T10:00:00Z")

  private val sourceProject   = project(sourceProjectId, organizationId)
  private val targetProject   = project(targetProjectId, organizationId)
  private val otherProject    = project(otherProjectId, organizationId)
  private val visibleProjects = Seq(sourceProject, targetProject, otherProject)

  private val activeWorklog = WorkUnit(
    id = workUnitId,
    userId = authorId,
    organizationId = organizationId,
    projectId = sourceProjectId,
    date = WorkUnitDate(LocalDate.parse("2026-08-05")),
    duration = WorkUnitDuration(Duration.ofHours(8)),
    entryType = WorkUnitEntryType.Worklog,
    description = WorkUnitDescription("work"),
    taskId = None,
    state = WorkUnitState.Active,
    createdAt = WorkUnitCreatedAt(now),
    modifiedAt = WorkUnitModifiedAt(now),
    archivedAt = None
  )

  "scope" should {
    "derive authority from fresh organization and project membership records" in {
      val cases = Seq(
        "superadmin without an organization user" ->
          (snapshot(isSuperAdmin = true, organizationUser = None), WorkUnitOverrideScope.All),
        "organization owner" ->
          (snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Owner)))), WorkUnitOverrideScope.All),
        "organization manager" ->
          (snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager)))), WorkUnitOverrideScope.All),
        "employee owning the source project" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner))
          ), WorkUnitOverrideScope.Owned(Set(sourceProjectId))),
        "external owning the source project" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.External))),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner))
          ), WorkUnitOverrideScope.Owned(Set(sourceProjectId))),
        "employee project member" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Member))
          ), WorkUnitOverrideScope.None),
        "employee without a project role" ->
          (snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee)))), WorkUnitOverrideScope.None),
        "worklog author without a PM role" ->
          (snapshot(
            actor = authorId,
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee), user = authorId))
          ), WorkUnitOverrideScope.None),
        "archived organization user" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager), archived = true)),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner))
          ), WorkUnitOverrideScope.None),
        "archived project owner assignment" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.External))),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner, archived = true))
          ), WorkUnitOverrideScope.None),
        "project owner assignment belonging to another user" ->
          (snapshot(
            organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
            projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner, user = authorId))
          ), WorkUnitOverrideScope.None),
        "organization user belonging to another organization" ->
          (snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager), organization = otherOrgId))), WorkUnitOverrideScope.None)
      )

      cases.map { case (name, (input, _)) => name -> scope(input) } shouldEqual
        cases.map { case (name, (_, expected)) => name -> expected }
    }

    "prefer an active management role over project assignments in combined-role snapshots" in {
      val input = snapshot(
        organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee, OrganizationUserRole.Manager))),
        projectUsers = Seq(projectUser(targetProjectId, ProjectRole.Member, archived = true))
      )

      scope(input) shouldEqual WorkUnitOverrideScope.All
    }

    "drop revoked and archived project-owner roles when a fresh snapshot is evaluated" in {
      val base = snapshot(
        organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
        projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner))
      )

      scope(base) shouldEqual WorkUnitOverrideScope.Owned(Set(sourceProjectId))
      scope(base.copy(projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Member)))) shouldEqual WorkUnitOverrideScope.None
      scope(base.copy(projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner, archived = true)))) shouldEqual WorkUnitOverrideScope.None
    }

    "ignore owner assignments to foreign and missing project records" in {
      val foreignProjectId = projectId(50)
      val missingProjectId = projectId(60)
      val employee         = Some(orgUser(Seq(OrganizationUserRole.Employee)))
      val foreign = snapshot(
        organizationUser = employee,
        projectUsers = Seq(projectUser(foreignProjectId, ProjectRole.Owner)),
        projects = visibleProjects :+ project(foreignProjectId, otherOrgId)
      )
      val missing = snapshot(organizationUser = employee, projectUsers = Seq(projectUser(missingProjectId, ProjectRole.Owner)))

      Seq(scope(foreign), scope(missing)) shouldEqual Seq(WorkUnitOverrideScope.None, WorkUnitOverrideScope.None)
      Seq(
        permissions(foreign, activeWorklog.copy(projectId = foreignProjectId)),
        permissions(missing, activeWorklog.copy(projectId = missingProjectId))
      ).map(permissionFlags) shouldEqual Seq.fill(2)((false, false, false))
    }
  }

  "allowedProjectIds" should {
    "return every unique nonarchived same-organization project in deterministic UUID order for All" in {
      val archived = project(projectId(40), organizationId, archived = true)
      val foreign  = project(projectId(5), otherOrgId)
      val input = snapshot(
        isSuperAdmin = true,
        organizationUser = None,
        projects = Seq(sourceProject, archived, targetProject, sourceProject, foreign, otherProject)
      )

      allowedProjectIds(input) shouldEqual Seq(targetProjectId, otherProjectId, sourceProjectId)
    }

    "intersect active owned assignments with unique nonarchived same-organization projects" in {
      val archivedId = projectId(40)
      val foreignId  = projectId(5)
      val input = snapshot(
        organizationUser = Some(orgUser(Seq(OrganizationUserRole.External))),
        projectUsers = Seq(
          projectUser(sourceProjectId, ProjectRole.Owner),
          projectUser(targetProjectId, ProjectRole.Owner),
          projectUser(targetProjectId, ProjectRole.Owner),
          projectUser(archivedId, ProjectRole.Owner),
          projectUser(foreignId, ProjectRole.Owner)
        ),
        projects = Seq(sourceProject, targetProject, project(archivedId, organizationId, archived = true), project(foreignId, otherOrgId))
      )

      allowedProjectIds(input) shouldEqual Seq(targetProjectId, sourceProjectId)
    }
  }

  "permissions and classification authorization" should {
    "enable all PM actions for an eligible source and permit task-only classification" in {
      val input = snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager))))

      permissions(input, activeWorklog) shouldEqual WorkUnitOverridePermissions(
        canEdit = true,
        canDelete = true,
        canViewHistory = true,
        allowedProjectIds = Seq(targetProjectId, otherProjectId, sourceProjectId)
      )
      canEdit(input, activeWorklog, targetProjectId = None) should beTrue
    }

    "keep source scope independent from the active PM-target catalog" in {
      val archivedSource  = sourceProject.copy(archivedAt = Some(ProjectArchivedAt(now)), archivedBy = Some(actorId))
      val noActiveTargets = Seq(archivedSource)
      val manager         = snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager))), projects = noActiveTargets)
      val projectOwner = snapshot(
        organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
        projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner)),
        projects = noActiveTargets
      )

      Seq(manager, projectOwner).map(permissions(_, activeWorklog)) shouldEqual Seq.fill(2)(
        WorkUnitOverridePermissions(canEdit = true, canDelete = true, canViewHistory = true, allowedProjectIds = Seq.empty)
      )
      Seq(manager, projectOwner).map(canEdit(_, activeWorklog, None)) shouldEqual Seq(true, true)
      Seq(manager, projectOwner).map(canEdit(_, activeWorklog, Some(sourceProjectId))) shouldEqual Seq(false, false)
    }

    "use the source project for scope and restrict an owned actor's target to another owned project" in {
      val input = snapshot(
        organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee))),
        projectUsers = Seq(projectUser(sourceProjectId, ProjectRole.Owner), projectUser(targetProjectId, ProjectRole.Owner))
      )

      permissions(input, activeWorklog).canEdit should beTrue
      canEdit(input, activeWorklog, Some(targetProjectId)) should beTrue
      canEdit(input, activeWorklog, Some(otherProjectId)) should beFalse
      permissions(input, activeWorklog.copy(projectId = otherProjectId)).canEdit should beFalse
    }

    "reject archived and cross-organization target projects on fresh snapshots" in {
      val manager = snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager))))

      canEdit(
        manager.copy(projects = visibleProjects.map {
          case value if value.id == targetProjectId => value.copy(archivedAt = Some(ProjectArchivedAt(now)))
          case value                                => value
        }),
        activeWorklog,
        Some(targetProjectId)
      ) should beFalse

      canEdit(manager.copy(projects = visibleProjects :+ project(projectId(50), otherOrgId)), activeWorklog, Some(projectId(50))) should beFalse
    }

    "reject foreign and missing source project records for every all-scope actor" in {
      val foreignProjectId = projectId(50)
      val foreignSource    = activeWorklog.copy(projectId = foreignProjectId)
      val missingSource    = activeWorklog.copy(projectId = projectId(60))
      val projects         = visibleProjects.filterNot(_.id == sourceProjectId) :+ project(foreignProjectId, otherOrgId)
      val allScopeActors = Seq(
        snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager))), projects = projects),
        snapshot(isSuperAdmin = true, organizationUser = None, projects = projects)
      )

      allScopeActors.flatMap(input => Seq(permissions(input, foreignSource), permissions(input, missingSource))).map(permissionFlags) shouldEqual
        Seq.fill(4)((false, false, false))
    }

    "reject every ineligible work-unit type, state, archive marker, and organization" in {
      val manager = snapshot(organizationUser = Some(orgUser(Seq(OrganizationUserRole.Manager))))
      val rejected = Seq(
        "note"               -> activeWorklog.copy(entryType = WorkUnitEntryType.Note),
        "draft"              -> activeWorklog.copy(state = WorkUnitState.Draft),
        "inactive"           -> activeWorklog.copy(state = WorkUnitState.Inactive),
        "archived state"     -> activeWorklog.copy(state = WorkUnitState.Archived),
        "deleted"            -> activeWorklog.copy(state = WorkUnitState.Deleted),
        "gdpr anonymized"    -> activeWorklog.copy(state = WorkUnitState.GdprAnonymized),
        "archived marker"    -> activeWorklog.copy(archivedAt = Some(WorkUnitArchivedAt(now))),
        "other organization" -> activeWorklog.copy(organizationId = otherOrgId)
      )

      rejected.map { case (name, workUnit) => name -> permissions(manager, workUnit) } shouldEqual
        rejected.map { case (name, _) =>
          name -> WorkUnitOverridePermissions(
            canEdit = false,
            canDelete = false,
            canViewHistory = false,
            allowedProjectIds = Seq(targetProjectId, otherProjectId, sourceProjectId)
          )
        }
    }

    "not turn ordinary report visibility or authorship into PM permission" in {
      val authorWithoutPm = snapshot(actor = authorId, organizationUser = Some(orgUser(Seq(OrganizationUserRole.Employee), user = authorId)))

      permissions(authorWithoutPm, activeWorklog).canEdit should beFalse
      canEdit(authorWithoutPm, activeWorklog, None) should beFalse
    }
  }

  private def snapshot(
    actor: UserId = actorId,
    isSuperAdmin: Boolean = false,
    organizationUser: Option[OrganizationUser],
    projectUsers: Seq[ProjectUser] = Seq.empty,
    projects: Seq[Project] = visibleProjects
  ): WorkUnitOverridePolicySnapshot =
    WorkUnitOverridePolicySnapshot(actor, isSuperAdmin, organizationId, organizationUser, projectUsers, projects)

  private def orgUser(
    roles: Seq[OrganizationUserRole],
    archived: Boolean = false,
    user: UserId = actorId,
    organization: OrganizationId = organizationId
  ): OrganizationUser =
    OrganizationUser(
      id = OrganizationUserId(uuid(100)),
      organizationId = organization,
      userId = user,
      roles = roles,
      archivedAt = Option.when(archived)(OrganizationUserArchivedAt(now))
    )

  private def projectUser(
    project: ProjectId,
    role: ProjectRole,
    archived: Boolean = false,
    user: UserId = actorId
  ): ProjectUser =
    ProjectUser(
      id = ProjectUserId(UUID.randomUUID()),
      projectId = project,
      userId = user,
      role = role,
      createdAt = ProjectUserCreatedAt(now),
      archivedAt = Option.when(archived)(ProjectUserArchivedAt(now))
    )

  private def project(
    id: ProjectId,
    organization: OrganizationId,
    archived: Boolean = false
  ): Project =
    Project(
      id = id,
      name = ProjectName(s"project-${id.toString}"),
      description = None,
      organizationId = organization,
      clientId = ClientId(UUID.randomUUID()),
      createdAt = ProjectCreatedAt(now),
      createdBy = actorId,
      modifiedAt = ProjectModifiedAt(now),
      modifiedBy = actorId,
      archivedAt = Option.when(archived)(ProjectArchivedAt(now)),
      archivedBy = Option.when(archived)(actorId)
    )

  private def permissionFlags(permissions: WorkUnitOverridePermissions): (Boolean, Boolean, Boolean) =
    (permissions.canEdit, permissions.canDelete, permissions.canViewHistory)

  private def uuid(value: Int): UUID            = UUID.fromString(f"00000000-0000-0000-0000-$value%012d")
  private def userId(value: Int): UserId        = UserId(uuid(value))
  private def orgId(value: Int): OrganizationId = OrganizationId(uuid(value))
  private def projectId(value: Int): ProjectId  = ProjectId(uuid(value))
}
