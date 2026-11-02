package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Json}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PostgresIOTransactor}
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideHistory, WorkUnitOverrideState, WorkUnitOverrideUndo, WorkUnitTaskOverride}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.{WorkUnitOverrideHistoryRepository, WorkUnitRepository}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitOverrideResult._
import slick.dbio.DBIO

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

final case class ReplaceWorkUnitOverrideRequest(
  expectedRevision: Long,
  projectId: Option[ProjectId],
  taskId: WorkUnitTaskOverride)

final case class UndoWorkUnitOverrideRequest(expectedRevision: Long, targetEventId: UUID)

object ReplaceWorkUnitOverrideRequest {
  implicit val decoder: Decoder[ReplaceWorkUnitOverrideRequest] = Decoder.instance { cursor =>
    if (!cursor.keys.exists(_.toSet.contains("projectId"))) Left(DecodingFailure("projectId is required (null means inherit)", cursor.history))
    else
      for {
        revision <- cursor.get[Long]("expectedRevision")
        project  <- cursor.get[Option[ProjectId]]("projectId")
        task     <- cursor.get[WorkUnitTaskOverride]("taskId")
      } yield ReplaceWorkUnitOverrideRequest(revision, project, task)
  }
}

sealed trait WorkUnitOverrideResult
object WorkUnitOverrideResult {
  final case class Ok(dto: WorkUnitDto)                   extends WorkUnitOverrideResult
  final case class StaleRevision(currentRevision: Long)   extends WorkUnitOverrideResult
  final case class StaleUndoTarget(currentRevision: Long) extends WorkUnitOverrideResult
  final case class InvalidRequest(field: String)          extends WorkUnitOverrideResult
  case object Forbidden                                   extends WorkUnitOverrideResult
  case object WorkUnitNotFound                            extends WorkUnitOverrideResult
  case object ProjectNotFound                             extends WorkUnitOverrideResult
  case object ProjectUnavailable                          extends WorkUnitOverrideResult
}

final case class WorkUnitOverrideRevertedByDto(
  eventId: UUID,
  actor: WorkUnitOverrideActorDto,
  changedAt: Instant)

final case class WorkUnitOverrideHistoryDto(
  id: UUID,
  version: Long,
  operation: String,
  actor: WorkUnitOverrideActorDto,
  changedAt: Instant,
  provenance: String,
  before: Option[Json],
  after: Option[Json],
  legacyBefore: Option[Json],
  legacyAfter: Option[Json],
  parentCorrectionId: Option[UUID] = None,
  revertsEventId: Option[UUID] = None,
  revertedBy: Option[WorkUnitOverrideRevertedByDto] = None)

final case class WorkUnitOverrideHistoryPage(
  count: Int,
  pageNumber: Int,
  pageSize: Int,
  upToVersion: Long,
  results: Seq[WorkUnitOverrideHistoryDto])

private[services] final case class WorkUnitOverrideContext(
  actor: User,
  workUnit: WorkUnit,
  snapshot: WorkUnitOverridePolicySnapshot,
  projects: Seq[Project],
  clients: Seq[Client],
  latest: Option[WorkUnitOverrideHistory],
  candidate: Option[WorkUnitOverrideHistory])

class WorkUnitOverrideService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  clientRepository: ClientRepository,
  userRepository: UserRepository,
  workUnitRepository: WorkUnitRepository,
  historyRepository: WorkUnitOverrideHistoryRepository,
  dtoService: WorkUnitDtoService
)(implicit
  runtime: IORuntime,
  clock: Clock[DBIO]) {
  private implicit val ec: ExecutionContext = runtime.compute

  def get(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId
  ): IO[WorkUnitOverrideResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead)(getAction(auth, slug, id))

  def undo(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    request: UndoWorkUnitOverrideRequest
  ): IO[WorkUnitOverrideResult] =
    transactor.executeTransactionally(undoAction(auth, slug, id, request))

  private[workunit] def undoAction(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    request: UndoWorkUnitOverrideRequest
  ): DBIO[WorkUnitOverrideResult] =
    if (request.expectedRevision < 0) DBIO.successful(InvalidRequest("expectedRevision"))
    else
      context(auth, slug, id, None, mutation = true, restoring = true).flatMap {
        case Left(error)                                                         => DBIO.successful(error)
        case Right(value) if value.workUnit.revision != request.expectedRevision => DBIO.successful(StaleRevision(value.workUnit.revision))
        case Right(value) =>
          value.candidate.filter(_.id == request.targetEventId) match {
            case None => DBIO.successful(StaleUndoTarget(value.workUnit.revision))
            case Some(target) =>
              WorkUnitOverrideUndo.restore(target) match {
                case Left(field) => DBIO.successful(InvalidRequest(field))
                case Right(desired) =>
                  val normalized = desired.normalized(value.workUnit)
                  validateTarget(value, normalized.projectId) match {
                    case Some(error) => DBIO.successful(error)
                    case None        => persist(value, normalized, "Undo", target.parentCorrectionId, Some(target.id))
                  }
              }
          }
      }
  def replace(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    request: ReplaceWorkUnitOverrideRequest
  ): IO[WorkUnitOverrideResult] =
    transactor.executeTransactionally(replaceAction(auth, slug, id, request))
  def delete(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    expectedRevision: Long
  ): IO[WorkUnitOverrideResult] =
    transactor.executeTransactionally(deleteAction(auth, slug, id, expectedRevision))

  private[workunit] def getAction(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId
  ): DBIO[WorkUnitOverrideResult] =
    context(auth, slug, id, None, mutation = false).flatMap {
      case Left(error)  => DBIO.successful(error)
      case Right(value) => currentDto(value).map(Ok.apply)
    }

  private[workunit] def replaceAction(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    request: ReplaceWorkUnitOverrideRequest
  ): DBIO[WorkUnitOverrideResult] =
    request.taskId.validated match {
      case Left(field)          => DBIO.successful(InvalidRequest(field))
      case Right((isSet, task)) => mutate(auth, slug, id, request.expectedRevision, WorkUnitOverrideState(request.projectId, isSet, task), "Replace")
    }

  private[workunit] def deleteAction(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    expectedRevision: Long
  ): DBIO[WorkUnitOverrideResult] =
    mutate(auth, slug, id, expectedRevision, WorkUnitOverrideState(), "Clear")

  private def mutate(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    expectedRevision: Long,
    desired: WorkUnitOverrideState,
    operation: String
  ): DBIO[WorkUnitOverrideResult] =
    if (expectedRevision < 0) DBIO.successful(InvalidRequest("expectedRevision"))
    else
      context(auth, slug, id, desired.projectId, mutation = true).flatMap {
        case Left(error)                                                 => DBIO.successful(error)
        case Right(value) if value.workUnit.revision != expectedRevision => DBIO.successful(StaleRevision(value.workUnit.revision))
        case Right(value) =>
          val normalized = desired.normalized(value.workUnit)
          validateTarget(value, normalized.projectId) match {
            case Some(error)                                                       => DBIO.successful(error)
            case None if value.workUnit.overrideState.sameDesiredState(normalized) => currentDto(value).map(Ok.apply)
            case None =>
              persist(value, normalized, operation, WorkUnitOverrideUndo.candidateId(value.latest, value.workUnit.overrideState.version), None)
          }
      }

  private def persist(
    value: Context,
    normalized: WorkUnitOverrideState,
    operation: String,
    parent: Option[UUID],
    reverts: Option[UUID]
  ): DBIO[WorkUnitOverrideResult] =
    for {
      now <- clock.realTime.map(time => Instant.ofEpochMilli(time.toMillis))
      state = normalized.copy(
                version = value.workUnit.overrideState.version + 1,
                hasHistory = true,
                lastChangedBy = Some(value.actor.id),
                lastChangedAt = Some(now)
              )
      updated = value.workUnit.copy(revision = value.workUnit.revision + 1, overrideState = state)
      event = WorkUnitOverrideHistory(
                UUID.randomUUID(),
                value.workUnit.id,
                state.version,
                operation,
                value.actor.id,
                value.actor.fullName,
                now,
                "WorkUnitOverride",
                Some(snapshot(value, value.workUnit)),
                Some(snapshot(value, updated)),
                parentCorrectionId = parent,
                revertsEventId = reverts
              )
      _         <- historyRepository.insert(event)
      persisted <- workUnitRepository.update(updated)
      fresh     <- persisted.fold[DBIO[WorkUnit]](DBIO.failed(new IllegalStateException("Locked work unit disappeared")))(DBIO.successful)
      ancestry  <- historyHead(fresh)
      dto       <- currentDto(value.copy(workUnit = fresh, latest = ancestry._1, candidate = ancestry._2))
    } yield Ok(dto)

  private type Context = WorkUnitOverrideContext
  private val Context = WorkUnitOverrideContext

  private def context(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    target: Option[ProjectId],
    mutation: Boolean,
    restoring: Boolean = false
  ): DBIO[Either[WorkUnitOverrideResult, Context]] =
    organizationRepository.find(OrganizationFilters(slug = Some(slug))).flatMap {
      case None => DBIO.successful(Left(WorkUnitNotFound))
      case Some(org) =>
        workUnitRepository.find(WorkUnitFilters(id = Some(id), organizationId = Some(org.id), includeArchived = true, forUpdate = mutation)).flatMap {
          case None => DBIO.successful(Left(WorkUnitNotFound))
          case Some(unit) =>
            historyHead(unit).flatMap { case (latest, candidate) =>
              userRepository.find(UserFilters(id = Some(auth.id), includeArchived = true, forUpdate = mutation)).flatMap {
                case None                                     => DBIO.successful(Left(Forbidden))
                case Some(actor) if actor.archivedAt.nonEmpty => DBIO.successful(Left(Forbidden))
                case Some(actor) =>
                  val restored =
                    if (restoring) candidate.flatMap(event => WorkUnitOverrideUndo.restore(event).toOption).flatMap(_.normalized(unit).projectId)
                    else None
                  val relevant = (Seq(unit.projectId, unit.effectiveProjectId) ++ target ++ restored).distinct.sortBy(_.toString)
                  for {
                    _ <- if (mutation) projectRepository.lockOrdered(ProjectFilters(ids = Some(relevant), includeArchived = true)).map(_ => ())
                         else DBIO.successful(())
                    member <-
                      organizationUserRepository.find(
                        OrganizationUserFilters(organizationId = Some(org.id), userId = Some(actor.id), includeArchived = true, forUpdate = mutation)
                      )
                    _ <- if (mutation)
                           projectUserRepository
                             .lockOrdered(ProjectUserFilters(projectIds = Some(relevant), userId = Some(actor.id), includeArchived = true))
                             .map(_ => ())
                         else DBIO.successful(())
                    projects     <- projectRepository.list(ProjectFilters(organizationIds = Some(Seq(org.id)), includeArchived = true))
                    projectUsers <- projectUserRepository.list(ProjectUserFilters(userId = Some(actor.id), includeArchived = true))
                    clients      <- clientRepository.list(ClientFilters(organizationIds = Some(Seq(org.id)), includeArchived = true))
                    policy  = WorkUnitOverridePolicySnapshot(actor.id, actor.isSuperAdmin, org.id, member, projectUsers, projects)
                    allowed = WorkUnitOverridePolicy.permissions(policy, unit)
                  } yield Either
                    .cond(
                      if (mutation) allowed.canEdit else allowed.canViewHistory,
                      Context(actor, unit, policy, projects, clients, latest, candidate),
                      Forbidden
                    )
              }
            }
        }
    }

  private def validateTarget(value: Context, target: Option[ProjectId]): Option[WorkUnitOverrideResult] =
    target.flatMap { id =>
      value.projects.find(_.id == id) match {
        case None                                                                              => Some(ProjectNotFound)
        case Some(project) if project.archivedAt.nonEmpty                                      => Some(ProjectUnavailable)
        case Some(_) if !WorkUnitOverridePolicy.allowedProjectIds(value.snapshot).contains(id) => Some(ProjectUnavailable)
        case Some(_)                                                                           => None
      }
    }

  private def historyHead(unit: WorkUnit): DBIO[(Option[WorkUnitOverrideHistory], Option[WorkUnitOverrideHistory])] =
    historyRepository.list(unit.id, unit.overrideState.version, Some(PageSizeAndNumber(1, 1))).flatMap { events =>
      val latest = events.headOption
      WorkUnitOverrideUndo.candidateId(latest, unit.overrideState.version) match {
        case Some(id) => historyRepository.find(unit.id, id, unit.overrideState.version).map(candidate => (latest, candidate))
        case None     => DBIO.successful((latest, None))
      }
    }

  private def undoMetadata(value: Context): DBIO[WorkUnitOverrideUndoDto] = {
    val candidateId = WorkUnitOverrideUndo.candidateId(value.latest, value.workUnit.overrideState.version)
    val permissions = WorkUnitOverridePolicy.permissions(value.snapshot, value.workUnit)
    candidateId match {
      case Some(id) =>
        val reason =
          if (!permissions.canEdit) Some("Forbidden")
          else
            value.candidate.flatMap(event => WorkUnitOverrideUndo.restore(event).toOption) match {
              case Some(state) if validateTarget(value, state.normalized(value.workUnit).projectId).isEmpty => None
              case _                                                                                        => Some("TargetUnavailable")
            }
        DBIO.successful(WorkUnitOverrideUndoDto(Some(id), reason.isEmpty, reason))
      case None =>
        val root = value.latest
          .filter(event =>
            event.version == value.workUnit.overrideState.version && event.operation == "Undo" && event.provenance == "WorkUnitOverride"
          )
          .flatMap(_.revertsEventId)
        val empty = value.workUnit.overrideState.version == 0 && value.latest.isEmpty
        root
          .fold[DBIO[Option[WorkUnitOverrideHistory]]](DBIO.successful(None))(id =>
            historyRepository.find(value.workUnit.id, id, value.workUnit.overrideState.version)
          )
          .map { reverted =>
            val exhausted =
              reverted.exists(event => event.version == 1 && event.parentCorrectionId.isEmpty && WorkUnitOverrideUndo.restore(event).isRight)
            WorkUnitOverrideUndoDto(None, false, Some(if (empty || exhausted) "NoCorrection" else "LegacyBoundary"))
          }
    }
  }

  private def currentDto(value: Context): DBIO[WorkUnitDto] =
    for {
      dto  <- dtoService.prepareDto(value.workUnit)
      undo <- undoMetadata(value)
    } yield dto.copy(`override` =
      dto.`override`.copy(permissions = WorkUnitOverrideDto.permissions(value.snapshot, value.workUnit), undo = Some(undo))
    )

  private def snapshot(context: Context, unit: WorkUnit): Json = {
    def projectClient(id: ProjectId): Json = {
      val project = context.projects.find(_.id == id).getOrElse(throw new IllegalStateException("Work unit project missing"))
      val client  = context.clients.find(_.id == project.clientId).getOrElse(throw new IllegalStateException("Work unit client missing"))
      Json.obj(
        "projectId"   -> project.id.asJson,
        "projectName" -> project.name.asJson,
        "clientId"    -> client.id.asJson,
        "clientName"  -> client.name.asJson
      )
    }
    Json.obj(
      "source"            -> Json.obj("projectClient" -> projectClient(unit.projectId), "taskId" -> unit.taskId.asJson),
      "effective"         -> Json.obj("projectClient" -> projectClient(unit.effectiveProjectId), "taskId" -> unit.effectiveTaskId.asJson),
      "projectId"         -> unit.overrideState.projectId.asJson,
      "taskIdOverrideSet" -> Json.fromBoolean(unit.overrideState.taskIdOverrideSet),
      "taskId"            -> unit.overrideState.desiredTask.asJson
    )
  }

  def history(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    upToVersion: Long,
    pageSize: Option[Int],
    pageNumber: Option[Int]
  ): IO[Either[WorkUnitOverrideResult, WorkUnitOverrideHistoryPage]] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead)(historyAction(auth, slug, id, upToVersion, pageSize, pageNumber))

  private[workunit] def historyAction(
    auth: AuthContext,
    slug: OrganizationSlug,
    id: WorkUnitId,
    upToVersion: Long,
    pageSize: Option[Int],
    pageNumber: Option[Int]
  ): DBIO[Either[WorkUnitOverrideResult, WorkUnitOverrideHistoryPage]] = {
    val pagination = (pageSize, pageNumber) match {
      case _ if upToVersion < 0 => Left(InvalidRequest("upToVersion"))
      case (None, None)         => Right(PageSizeAndNumber(25, 1))
      case (Some(size), Some(number)) if size >= 1 && size <= 100 && number >= 1 && (number.toLong - 1) * size <= Int.MaxValue =>
        Right(PageSizeAndNumber(size, number))
      case (Some(size), _) if size < 1 || size > 100 => Left(InvalidRequest("pageSize"))
      case (None, _)                                 => Left(InvalidRequest("pageSize"))
      case _                                         => Left(InvalidRequest("pageNumber"))
    }
    pagination match {
      case Left(error) => DBIO.successful(Left(error))
      case Right(page) =>
        context(auth, slug, id, None, mutation = false).flatMap {
          case Left(error)                                                        => DBIO.successful(Left(error))
          case Right(value) if upToVersion > value.workUnit.overrideState.version => DBIO.successful(Left(InvalidRequest("upToVersion")))
          case Right(_) =>
            for {
              count     <- historyRepository.count(id, upToVersion)
              rows      <- historyRepository.list(id, upToVersion, Some(page))
              reversals <- historyRepository.reversals(id, rows.map(_.id), upToVersion)
              revertedBy = reversals.flatMap(event => event.revertsEventId.map(_ -> event)).toMap
            } yield Right(
              WorkUnitOverrideHistoryPage(count, page.number, page.size, upToVersion, rows.map(event => historyDto(event, revertedBy.get(event.id))))
            )
        }
    }
  }

  private def historyDto(event: WorkUnitOverrideHistory, reversal: Option[WorkUnitOverrideHistory]): WorkUnitOverrideHistoryDto = {
    def legacy(value: pl.iterators.epoca.workunit.domain.WorkUnitOverrideLegacySnapshot): Json = {
      val projectClient = value.projectId
        .map(id =>
          Json.obj(
            "projectId"   -> id.asJson,
            "projectName" -> value.projectName.asJson,
            "clientId"    -> value.clientId.asJson,
            "clientName"  -> value.clientName.asJson
          )
        )
        .getOrElse(Json.Null)
      Json.obj("projectClient" -> projectClient, "taskId" -> value.taskId.asJson)
    }
    WorkUnitOverrideHistoryDto(
      event.id,
      event.version,
      event.operation,
      WorkUnitOverrideActorDto(event.actorId, event.actorDisplayName),
      event.changedAt,
      event.provenance,
      event.before,
      event.after,
      Option.when(event.provenance == "LegacyPmSnapshot")(legacy(event.legacyBefore)),
      Option.when(event.provenance == "LegacyPmSnapshot")(legacy(event.legacyAfter)),
      event.parentCorrectionId,
      event.revertsEventId,
      reversal.map(undo => WorkUnitOverrideRevertedByDto(undo.id, WorkUnitOverrideActorDto(undo.actorId, undo.actorDisplayName), undo.changedAt))
    )
  }
}
