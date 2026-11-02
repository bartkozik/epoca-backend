package pl.iterators.epoca.workunit.services

import cats.Monad
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.adapters.OAuthAccessPolicy
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitEntryPolicy}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateResult._
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class WorkUnitUpdateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitRepository: WorkUnitRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  userRepository: UserRepository,
  workUnitDtoService: WorkUnitDtoService,
  workUnitBusinessClock: WorkUnitBusinessClock
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitUpdateResult](ioRuntime)
    with LoggingSupport {

  def update(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    request: WorkUnitUpdateRequest
  ): IO[WorkUnitUpdateResult] =
    transactor.executeTransactionally(updateAction(authContext, organizationSlug, workUnitId, request))

  private[workunit] def updateAction(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    request: WorkUnitUpdateRequest
  ): DBIO[WorkUnitUpdateResult] =
    (for {
      _            <- log(authContext, organizationSlug)
      organization <- findOrganization(organizationSlug)
      orgUser      <- findOrganizationUser(authContext, organization)
      _            <- lockProjectTableWhenReferenced(request)
      workUnit     <- findWorkUnit(workUnitId, organization)
      _            <- validateHumanOwner(workUnit)
      _            <- checkAccessToWorkUnit(authContext, workUnit, orgUser)
      _            <- validateWorkUnitState(authContext, workUnit)
      _            <- validateEffectiveTime(workUnit, request)
      _            <- checkProjectExists(organization, request, workUnit)
      _            <- checkAccessToProject(authContext, request, workUnit)
      _            <- checkAssignmentPeriod(authContext, orgUser, request, workUnit)
      result       <- persistAuthorized(workUnit, request)
    } yield result).run

  private[epoca] def updateOAuthAction(
    policy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    request: WorkUnitUpdateRequest
  ): DBIO[WorkUnitUpdateResult] =
    (for {
      organization <- findOAuthOrganization(policy, organizationSlug)
      _            <- lockProjectTableWhenReferenced(request)
      workUnit     <- findWorkUnit(workUnitId, organization)
      _            <- ensureOAuthCurrentProjectAllowed(policy, workUnit)
      _            <- validateHumanOwner(workUnit)
      _            <- ensureOAuthTargetProjectAllowed(policy, request, workUnit)
      _            <- checkProjectExists(organization, request, workUnit)
      _            <- validateOAuthWorkUnitState(workUnit)
      _            <- validateEffectiveTime(workUnit, request)
      result       <- persistAuthorized(workUnit, request)
    } yield result).run

  private def log(authContext: AuthContext, organizationSlug: OrganizationSlug): StepDBIO[Unit] =
    Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug").seal

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def findOAuthOrganization(policy: OAuthAccessPolicy, organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(organizationSlug)))
      .valueOrF(Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound))

  private def ensureOAuthCurrentProjectAllowed(policy: OAuthAccessPolicy, workUnit: WorkUnit): StepDBIO[Unit] =
    Monad[DBIO]
      .pure(policy.allows(workUnit.projectId) && policy.allows(workUnit.effectiveProjectId))
      .ensureF(identity, Logger[DBIO].warning(s"workUnit ${workUnit.id} is outside OAuth policy").as(WorkUnitNotFound))
      .void

  private def ensureOAuthTargetProjectAllowed(
    policy: OAuthAccessPolicy,
    request: WorkUnitUpdateRequest,
    workUnit: WorkUnit
  ): StepDBIO[Unit] =
    request.projectId.filterNot(_ == workUnit.projectId).fold(().liftSealed[DBIO, WorkUnitUpdateResult]) { projectId =>
      Monad[DBIO]
        .pure(policy.allows(projectId))
        .ensureF(identity, Logger[DBIO].warning(s"project $projectId is outside OAuth policy").as(ProjectNotFound))
        .void
    }

  private def findOrganizationUser(authContext: AuthContext, organization: Organization): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) None.liftSealed[DBIO, WorkUnitUpdateResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)

  private def lockProjectTableWhenReferenced(request: WorkUnitUpdateRequest): StepDBIO[Unit] =
    request.projectId.fold(().liftSealed[DBIO, WorkUnitUpdateResult]) { _ =>
      projectRepository.lockTableForReference().seal
    }

  private def findWorkUnit(workUnitId: WorkUnitId, organization: Organization): StepDBIO[WorkUnit] =
    workUnitRepository
      .find(WorkUnitFilters(id = Some(workUnitId), organizationId = Some(organization.id), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"workUnit $workUnitId does not exist").as(WorkUnitNotFound)
      }

  private def validateHumanOwner(workUnit: WorkUnit): StepDBIO[Unit] = {
    val notFound = Logger[DBIO].error(s"workUnit ${workUnit.id} does not exist").as(WorkUnitNotFound)

    userRepository
      .find(UserFilters(id = Some(workUnit.userId)))
      .valueOrF(notFound)
      .ensureF(_.accountType == UserAccountType.Human, notFound)
      .void
  }

  private def checkAccessToWorkUnit(
    authContext: AuthContext,
    workUnit: WorkUnit,
    orgUser: Option[OrganizationUser]
  ): StepDBIO[Unit] =
    if (authContext.isSuperAdmin || orgUser.exists(_.roles.contains(OrganizationUserRole.Owner)))
      ().liftSealed[DBIO, WorkUnitUpdateResult]
    else
      Monad[DBIO]
        .pure(workUnit.userId == authContext.id)
        .ensureF(
          identity,
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to workUnit ${workUnit.id}")
            .as(NoAccessToWorkUnit)
        )
        .void

  private def validateWorkUnitState(authContext: AuthContext, workUnit: WorkUnit): StepDBIO[Unit] =
    Monad[DBIO]
      .pure(WorkUnitState.editableStates.contains(workUnit.state))
      .ensureF(
        identity,
        Logger[DBIO]
          .warning(s"user ${authContext.id} tried to edit uneditable workUnit ${workUnit.id}")
          .as(WorkUnitInactive)
      )
      .void

  private def validateOAuthWorkUnitState(workUnit: WorkUnit): StepDBIO[Unit] =
    Monad[DBIO]
      .pure(WorkUnitState.editableStates.contains(workUnit.state))
      .ensureF(identity, Logger[DBIO].warning(s"OAuth tried to edit uneditable workUnit ${workUnit.id}").as(WorkUnitInactive))
      .void

  private def validateEffectiveTime(workUnit: WorkUnit, request: WorkUnitUpdateRequest): StepDBIO[Unit] = {
    val effectiveDuration = request.duration.getOrElse(workUnit.duration)
    val effectiveDate     = request.date.getOrElse(workUnit.date)
    val shouldValidateDate =
      request.date.isDefined || (workUnit.state == WorkUnitState.Draft && request.shouldFinalize)

    for {
      _ <- WorkUnitEntryPolicy
             .normalizeDuration(workUnit.entryType, Some(effectiveDuration))
             .leftMap(mapPolicyError)
             .rethrow[DBIO]
      _ <-
        if (shouldValidateDate)
          WorkUnitEntryPolicy
            .validateDate(workUnit.entryType, effectiveDate, workUnitBusinessClock.businessToday)
            .leftMap(mapPolicyError)
            .rethrow[DBIO]
        else ().liftSealed[DBIO, WorkUnitUpdateResult]
    } yield ()
  }

  private def mapPolicyError(error: WorkUnitEntryPolicy.Error): WorkUnitUpdateResult =
    error match {
      case WorkUnitEntryPolicy.DurationRequired | WorkUnitEntryPolicy.NonPositiveWorklogDuration => NonPositiveDuration
      case WorkUnitEntryPolicy.NonNeutralNoteDuration                                            => NonNeutralNoteDuration
      case WorkUnitEntryPolicy.FutureWorklogDate                                                 => FutureWorklogDate
    }

  private def checkProjectExists(
    organization: Organization,
    request: WorkUnitUpdateRequest,
    oldWorkUnit: WorkUnit
  ): StepDBIO[Unit] = {
    val projectId = if (request.projectId.contains(oldWorkUnit.projectId)) None else request.projectId
    projectId.fold(().liftSealed[DBIO, WorkUnitUpdateResult]) { projectId =>
      projectRepository
        .find(ProjectFilters(id = Some(projectId), organizationIds = Some(Seq(organization.id))))
        .valueOrF {
          Logger[DBIO].error(s"project $projectId does not exist").as(ProjectNotFound)
        }
        .void
    }
  }

  private def checkAccessToProject(
    authContext: AuthContext,
    request: WorkUnitUpdateRequest,
    oldWorkUnit: WorkUnit
  ): StepDBIO[Unit] = {
    val projectId = if (request.projectId.contains(oldWorkUnit.projectId)) None else request.projectId
    if (authContext.isSuperAdmin) ().liftSealed[DBIO, WorkUnitUpdateResult]
    else
      projectId.fold(().liftSealed[DBIO, WorkUnitUpdateResult]) { projectId =>
        projectUserRepository
          .find(
            ProjectUserFilters(
              projectIds = Some(Seq(projectId)),
              userId = Some(authContext.id),
              includeArchived = true,
              notDeletedAt = Some(oldWorkUnit.createdAt)
            )
          )
          .valueOrF {
            Logger[DBIO].warning(s"user ${authContext.id} has no access to project $projectId").as(NoAccessToProject)
          }
          .void
      }
  }

  // A detached user may still edit worklogs from the period of the assignment, but cannot move a worklog
  // (to another date or project) outside of that period. Active assignments are not restricted.
  private def checkAssignmentPeriod(
    authContext: AuthContext,
    orgUser: Option[OrganizationUser],
    request: WorkUnitUpdateRequest,
    workUnit: WorkUnit
  ): StepDBIO[Unit] = {
    val targetProjectId = request.projectId.getOrElse(workUnit.projectId)
    val targetDate      = request.date.getOrElse(workUnit.date)
    val placementChange = targetProjectId != workUnit.projectId || targetDate != workUnit.date
    val zone            = workUnitBusinessClock.zoneId

    def covers(projectUser: ProjectUser): Boolean =
      projectUser.archivedAt.isEmpty || {
        val from = projectUser.createdAt.atZone(zone).toLocalDate
        val to   = projectUser.archivedAt.map(_.atZone(zone).toLocalDate)
        !targetDate.isBefore(from) && to.forall(!targetDate.isAfter(_))
      }

    if (authContext.isSuperAdmin || orgUser.exists(_.hasManagementRole) || !placementChange)
      ().liftSealed[DBIO, WorkUnitUpdateResult]
    else {
      val covering: DBIO[Option[ProjectUser]] =
        projectUserRepository
          .list(ProjectUserFilters(projectIds = Some(Seq(targetProjectId)), userId = Some(workUnit.userId), includeArchived = true))
          .map(_.find(covers))
      covering.valueOrF {
        Logger[DBIO]
          .warning(s"user ${workUnit.userId} has no assignment to project $targetProjectId on $targetDate")
          .as(NoAccessToProject)
      }.void
    }
  }

  private def prepareWorkUnit(workUnit: WorkUnit, request: WorkUnitUpdateRequest): StepDBIO[WorkUnit] =
    Clock[DBIO].instantNow.seal.map {
      workUnit.updated(request, _)
    }

  private def persistAuthorized(workUnit: WorkUnit, request: WorkUnitUpdateRequest): StepDBIO[WorkUnitUpdateResult] =
    for {
      updated     <- prepareWorkUnit(workUnit, request)
      persisted   <- updateWorkUnit(updated)
      workUnitDto <- workUnitDtoService.prepareDto(persisted).seal
    } yield Updated(workUnitDto)

  private def updateWorkUnit(workUnit: WorkUnit): StepDBIO[WorkUnit] =
    (Logger[DBIO].info(s"successfully updated workUnit ${workUnit.id}") *>
      workUnitRepository.update(workUnit)).valueOr(WorkUnitNotFound)

}

object WorkUnitUpdateService {
  final case class WorkUnitUpdateRequest(
    projectId: Option[ProjectId],
    duration: Option[WorkUnitDuration],
    description: Option[WorkUnitDescription],
    taskId: Option[WorkUnitTaskId],
    date: Option[WorkUnitDate],
    shouldFinalize: Boolean)

  final case class WorkUnitUpdatePatch(
    projectId: Option[ProjectId],
    date: Option[WorkUnitDate],
    duration: Option[WorkUnitDuration],
    description: Option[WorkUnitDescription],
    taskId: Option[WorkUnitTaskId])

  sealed trait WorkUnitUpdateResult

  object WorkUnitUpdateResult {
    final case object OrganizationNotFound          extends WorkUnitUpdateResult
    final case object NoAccessToOrganization        extends WorkUnitUpdateResult
    final case object WorkUnitNotFound              extends WorkUnitUpdateResult
    final case object NoAccessToWorkUnit            extends WorkUnitUpdateResult
    final case object ProjectNotFound               extends WorkUnitUpdateResult
    final case object NoAccessToProject             extends WorkUnitUpdateResult
    final case object WorkUnitInactive              extends WorkUnitUpdateResult
    final case object NonPositiveDuration           extends WorkUnitUpdateResult
    final case object NonNeutralNoteDuration        extends WorkUnitUpdateResult
    final case object FutureWorklogDate             extends WorkUnitUpdateResult
    final case class Updated(workUnit: WorkUnitDto) extends WorkUnitUpdateResult
  }
}
