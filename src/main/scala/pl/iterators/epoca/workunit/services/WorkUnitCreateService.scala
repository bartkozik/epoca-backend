package pl.iterators.epoca.workunit.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.adapters.OAuthAccessPolicy
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.WorkUnitEntryPolicy.{
  DurationRequired,
  FutureWorklogDate => PolicyFutureWorklogDate,
  NonNeutralNoteDuration => PolicyNonNeutralNoteDuration,
  NonPositiveWorklogDuration
}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitEntryPolicy}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateResult
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateResult._
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

final case class WorkUnitCreateRequest(
  projectId: ProjectId,
  date: WorkUnitDate,
  duration: Option[WorkUnitDuration],
  description: WorkUnitDescription,
  taskId: Option[WorkUnitTaskId],
  isDraft: Boolean,
  entryType: Option[WorkUnitEntryType] = None,
  userId: Option[UserId] = None)

case class WorkUnitCreateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitRepository: WorkUnitRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  workUnitDtoService: WorkUnitDtoService,
  userRepository: UserRepository,
  workUnitBusinessClock: WorkUnitBusinessClock
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitCreateResult](ioRuntime)
    with LoggingSupport {

  def create(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: WorkUnitCreateRequest
  ): IO[WorkUnitCreateResult] =
    transactor.executeTransactionally(createAction(authContext, organizationSlug, request))

  private[workunit] def createAction(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: WorkUnitCreateRequest
  ): DBIO[WorkUnitCreateResult] = {
    val normalized = WorkUnitCreateRequestNormalizer.normalize(authContext.id, request)

    (for {
      _               <- log(authContext, organizationSlug, normalized)
      organization    <- findOrganization(organizationSlug)
      actorMembership <- checkAccessToOrganization(authContext, organization)
      _               <- authorizeTargetUser(authContext, normalized, actorMembership)
      _               <- findActiveTargetUser(normalized.targetUserId)
      _               <- findActiveTargetMembership(authContext, organization, normalized, actorMembership)
      _               <- findProject(organization, normalized)
      _               <- checkAccessToProject(authContext, normalized, actorMembership)
      _               <- checkForeignWorklogTargetAssignment(authContext, normalized)
      result          <- persistAuthorized(organization, normalized)
    } yield result).run
  }

  private[epoca] def createOAuthAction(
    policy: OAuthAccessPolicy,
    organizationSlug: OrganizationSlug,
    request: WorkUnitCreateRequest
  ): DBIO[WorkUnitCreateResult] =
    request.userId match {
      case None => DBIO.successful(TargetUserNotFound)
      case Some(targetUserId) =>
        val normalized = WorkUnitCreateRequestNormalizer.normalize(targetUserId, request)

        (for {
          organization <- findOAuthOrganization(policy, organizationSlug)
          _            <- ensureOAuthProjectAllowed(policy, normalized.projectId)
          _            <- findActiveTargetUser(normalized.targetUserId)
          _            <- findOAuthTargetMembership(organization, normalized.targetUserId)
          _            <- findProject(organization, normalized)
          _            <- checkOAuthTargetAssignment(normalized)
          result       <- persistAuthorized(organization, normalized)
        } yield result).run
    }

  private def log(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: NormalizedWorkUnitCreateRequest
  ): StepDBIO[Unit] =
    Logger[DBIO]
      .info(s"auth.id=${authContext.id}, organization=$organizationSlug, targetUserId=${request.targetUserId}, entryType=${request.entryType}")
      .seal

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

  private def ensureOAuthProjectAllowed(policy: OAuthAccessPolicy, projectId: ProjectId): StepDBIO[Unit] =
    Sync[DBIO]
      .pure(policy.allows(projectId))
      .ensureF(identity, Logger[DBIO].warning(s"project $projectId is outside OAuth policy").as(ProjectNotFound))
      .void

  private def findOAuthTargetMembership(organization: Organization, targetUserId: UserId): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(targetUserId)))
      .valueOrF(Logger[DBIO].warning(s"target user is unavailable in organization ${organization.id}").as(TargetUserNotFound))
      .void

  private def checkOAuthTargetAssignment(request: NormalizedWorkUnitCreateRequest): StepDBIO[Unit] =
    projectUserRepository
      .find(ProjectUserFilters(projectIds = Some(Seq(request.projectId)), userId = Some(request.targetUserId)))
      .valueOrF(
        Logger[DBIO]
          .warning(s"target user is not assigned to project ${request.projectId}")
          .as(TargetUserNotAssignedToProject)
      )
      .void

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) None.liftSealed[DBIO, WorkUnitCreateResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)

  private def authorizeTargetUser(
    authContext: AuthContext,
    request: NormalizedWorkUnitCreateRequest,
    actorMembership: Option[OrganizationUser]
  ): StepDBIO[Unit] =
    Sync[DBIO]
      .pure(
        request.targetUserId == authContext.id ||
          authContext.isSuperAdmin ||
          actorMembership.exists(_.roles.contains(OrganizationUserRole.Owner))
      )
      .ensureF(
        identity,
        Logger[DBIO]
          .warning(s"user ${authContext.id} has no access to target user ${request.targetUserId}")
          .as(NoAccessToTargetUser)
      )
      .void

  private def findActiveTargetUser(targetUserId: UserId): StepDBIO[Unit] =
    (userRepository
      .find(UserFilters(id = Some(targetUserId)))
      .map(_.filter(user => user.archivedAt.isEmpty && user.accountType == UserAccountType.Human)): DBIO[Option[User]])
      .valueOrF(Logger[DBIO].warning(s"target user $targetUserId is unavailable").as(TargetUserNotFound))
      .void

  private def findActiveTargetMembership(
    authContext: AuthContext,
    organization: Organization,
    request: NormalizedWorkUnitCreateRequest,
    actorMembership: Option[OrganizationUser]
  ): StepDBIO[Unit] =
    if (!authContext.isSuperAdmin && request.targetUserId == authContext.id)
      Sync[DBIO]
        .pure(actorMembership)
        .valueOr(TargetUserNotFound)
        .void
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(request.targetUserId)))
        .valueOrF(Logger[DBIO].warning(s"target user is unavailable in organization ${organization.id}").as(TargetUserNotFound))
        .void

  private def findProject(organization: Organization, request: NormalizedWorkUnitCreateRequest): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(id = Some(request.projectId), organizationIds = Some(Seq(organization.id))))
      .valueOrF {
        Logger[DBIO].error(s"project ${request.projectId} does not exist").as(ProjectNotFound)
      }

  private def checkAccessToProject(
    authContext: AuthContext,
    request: NormalizedWorkUnitCreateRequest,
    orgUser: Option[OrganizationUser]
  ): StepDBIO[Unit] =
    if (authContext.isSuperAdmin || orgUser.exists(_.hasManagementRole)) ().liftSealed[DBIO, WorkUnitCreateResult]
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(request.projectId)), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO].warning(s"user ${authContext.id} has no access to project ${request.projectId}").as(NoAccessToProject)
        }
        .void

  private def checkForeignWorklogTargetAssignment(authContext: AuthContext, request: NormalizedWorkUnitCreateRequest): StepDBIO[Unit] =
    if (request.targetUserId == authContext.id || request.entryType == WorkUnitEntryType.Note)
      ().liftSealed[DBIO, WorkUnitCreateResult]
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(request.projectId)), userId = Some(request.targetUserId)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"target user is not assigned to project ${request.projectId}")
            .as(TargetUserNotAssignedToProject)
        }
        .void

  private def validateDuration(request: NormalizedWorkUnitCreateRequest): StepDBIO[WorkUnitDuration] =
    WorkUnitEntryPolicy
      .normalizeDuration(request.entryType, request.duration)
      .leftMap(mapPolicyError)
      .rethrow[DBIO]

  private def validateDate(request: NormalizedWorkUnitCreateRequest): StepDBIO[Unit] =
    WorkUnitEntryPolicy
      .validateDate(request.entryType, request.date, workUnitBusinessClock.businessToday)
      .leftMap(mapPolicyError)
      .rethrow[DBIO]

  private def persistAuthorized(organization: Organization, request: NormalizedWorkUnitCreateRequest): StepDBIO[WorkUnitCreateResult] =
    for {
      duration    <- validateDuration(request)
      _           <- validateDate(request)
      workUnit    <- prepareWorkUnit(request.targetUserId, organization, request, duration)
      result      <- insertWorkUnit(workUnit)
      workUnitDto <- workUnitDtoService.prepareDto(result).seal
    } yield Created(workUnitDto)

  private def mapPolicyError(error: WorkUnitEntryPolicy.Error): WorkUnitCreateResult =
    error match {
      case DurationRequired | NonPositiveWorklogDuration => NonPositiveDuration
      case PolicyNonNeutralNoteDuration                  => NonNeutralNoteDuration
      case PolicyFutureWorklogDate                       => FutureWorklogDate
    }

  private def prepareWorkUnit(
    targetUserId: UserId,
    organization: Organization,
    request: NormalizedWorkUnitCreateRequest,
    duration: WorkUnitDuration
  ): StepDBIO[WorkUnit] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, WorkUnitId].map { workUnitId =>
        request
          .into[WorkUnit]
          .enableDefaultValues
          .withFieldConst(_.id, workUnitId)
          .withFieldConst(_.userId, targetUserId)
          .withFieldConst(_.organizationId, organization.id)
          .withFieldConst(_.duration, duration)
          .withFieldConst(_.entryType, request.entryType)
          .withFieldConst(_.createdAt, WorkUnitCreatedAt(now))
          .withFieldConst(_.modifiedAt, WorkUnitModifiedAt(now))
          .withFieldConst(_.archivedAt, None)
          .withFieldComputed(_.state, r => if (r.isDraft) WorkUnitState.Draft else WorkUnitState.Active)
          .transform
      }
    }: DBIO[WorkUnit]).seal

  private def insertWorkUnit(workUnit: WorkUnit): StepDBIO[WorkUnit] =
    (Logger[DBIO].info(s"successfully created work unit for user${workUnit.userId}") *> workUnitRepository.insert(workUnit)).seal
}

object WorkUnitCreateService {
  // Preserve the historical import path while allowing schema derivation to evaluate
  // request defaults independently of this service companion.
  type WorkUnitCreateRequest = _root_.pl.iterators.epoca.workunit.services.WorkUnitCreateRequest
  val WorkUnitCreateRequest: _root_.pl.iterators.epoca.workunit.services.WorkUnitCreateRequest.type =
    _root_.pl.iterators.epoca.workunit.services.WorkUnitCreateRequest

  sealed trait WorkUnitCreateResult
  object WorkUnitCreateResult {
    final case class Created(workUnit: WorkUnitDto)  extends WorkUnitCreateResult
    final case object OrganizationNotFound           extends WorkUnitCreateResult
    final case object NoAccessToOrganization         extends WorkUnitCreateResult
    final case object ProjectNotFound                extends WorkUnitCreateResult
    final case object NoAccessToProject              extends WorkUnitCreateResult
    final case object NonPositiveDuration            extends WorkUnitCreateResult
    final case object TargetUserNotFound             extends WorkUnitCreateResult
    final case object NoAccessToTargetUser           extends WorkUnitCreateResult
    final case object TargetUserNotAssignedToProject extends WorkUnitCreateResult
    final case object NonNeutralNoteDuration         extends WorkUnitCreateResult
    final case object FutureWorklogDate              extends WorkUnitCreateResult
  }
}
