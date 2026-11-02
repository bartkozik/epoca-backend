package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDescription, WorkUnitDuration, WorkUnitId, WorkUnitTaskId}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.Response
import pl.iterators.epoca.workunit.idempotency.{WorkUnitIdempotencyResult, WorkUnitIdempotencyScope, WorkUnitWriteCommand}
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import pl.iterators.epoca.workunit.services.{WorkUnitCreateService, WorkUnitIdempotencyService, WorkUnitUpdateService, WorkUnitWriteOutcomeMapper}
import slick.dbio.DBIO

import java.util.UUID
import scala.concurrent.ExecutionContext

trait OAuthWorkUnitWriter {
  def create(
    policy: OAuthAccessPolicy,
    actorId: UserId,
    organizationSlug: OrganizationSlug,
    request: OAuthWorkUnitWriteAdapter.OAuthWorkUnitCreateRequest,
    idempotencyKey: Option[WorkUnitIdempotencyKey],
    correlationId: String
  ): IO[WorkUnitIdempotencyResult]

  def update(
    policy: OAuthAccessPolicy,
    actorId: UserId,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    request: WorkUnitUpdateRequest,
    idempotencyKey: Option[WorkUnitIdempotencyKey],
    correlationId: String
  ): IO[WorkUnitIdempotencyResult]
}

final class OAuthWorkUnitWriteAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  createService: WorkUnitCreateService,
  updateService: WorkUnitUpdateService,
  outcomeMapper: WorkUnitWriteOutcomeMapper,
  idempotencyService: WorkUnitIdempotencyService
)(implicit ec: ExecutionContext)
    extends OAuthWorkUnitWriter {
  import OAuthWorkUnitWriteAdapter._

  def create(
    policy: OAuthAccessPolicy,
    actorId: UserId,
    organizationSlug: OrganizationSlug,
    request: OAuthWorkUnitCreateRequest,
    idempotencyKey: Option[WorkUnitIdempotencyKey],
    correlationId: String
  ): IO[WorkUnitIdempotencyResult] = {
    val command = request.toWorkUnitCreateRequest
    execute(
      policy,
      actorId,
      organizationSlug,
      idempotencyKey,
      correlationId,
      WorkUnitWriteCommand.Create(command),
      result => outcomeMapper.create(result, correlationId),
      createService.createOAuthAction(policy, organizationSlug, command)
    )
  }

  def update(
    policy: OAuthAccessPolicy,
    actorId: UserId,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId,
    request: WorkUnitUpdateRequest,
    idempotencyKey: Option[WorkUnitIdempotencyKey],
    correlationId: String
  ): IO[WorkUnitIdempotencyResult] =
    execute(
      policy,
      actorId,
      organizationSlug,
      idempotencyKey,
      correlationId,
      WorkUnitWriteCommand.Update(workUnitId, request),
      result => outcomeMapper.update(result, correlationId),
      updateService.updateOAuthAction(policy, organizationSlug, workUnitId, request)
    )

  private def execute[Result](
    policy: OAuthAccessPolicy,
    actorId: UserId,
    organizationSlug: OrganizationSlug,
    idempotencyKey: Option[WorkUnitIdempotencyKey],
    correlationId: String,
    command: WorkUnitWriteCommand,
    mapOutcome: Result => pl.iterators.epoca.workunit.idempotency.WorkUnitWriteOutcome,
    action: => DBIO[Result]
  ): IO[WorkUnitIdempotencyResult] =
    idempotencyKey match {
      case None =>
        transactor.executeTransactionally(action).map(result => Response(mapOutcome(result), replayed = false))
      case Some(key) =>
        transactor.executeTransactionally(IsolationLevel.ReadCommitted) {
          organizationRepository
            .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(organizationSlug)))
            .flatMap {
              case Some(_) =>
                idempotencyService
                  .execute(WorkUnitIdempotencyScope(policy.organizationId, actorId, key), command, mapOutcome)(action)
                  .map(authorizeReplay(policy, command, correlationId, _))
              case None =>
                val result = command match {
                  case WorkUnitWriteCommand.Create(_) =>
                    outcomeMapper.create(WorkUnitCreateResult.OrganizationNotFound, correlationId)
                  case WorkUnitWriteCommand.Update(_, _) =>
                    outcomeMapper.update(WorkUnitUpdateResult.OrganizationNotFound, correlationId)
                }
                DBIO.successful(Response(result, replayed = false))
            }
        }
    }

  private def authorizeReplay(
    policy: OAuthAccessPolicy,
    command: WorkUnitWriteCommand,
    correlationId: String,
    result: WorkUnitIdempotencyResult
  ): WorkUnitIdempotencyResult = result match {
    case Response(outcome, true)
        if outcome.status >= 200 && outcome.status < 300 &&
          policy.projectAccess != OAuthProjectAccess.AllProjects && !cachedProjectsAllowed(policy, outcome.body) =>
      val denied = command match {
        case WorkUnitWriteCommand.Create(_)    => outcomeMapper.create(WorkUnitCreateResult.ProjectNotFound, correlationId)
        case WorkUnitWriteCommand.Update(_, _) => outcomeMapper.update(WorkUnitUpdateResult.WorkUnitNotFound, correlationId)
      }
      Response(denied, replayed = false)
    case _ => result
  }

  private def cachedProjectsAllowed(policy: OAuthAccessPolicy, body: Json): Boolean = {
    def fieldAllowed(field: String): Boolean =
      body.hcursor.downField(field).get[UUID]("projectId").exists(id => policy.allows(ProjectId(id)))

    // Authorize the stored response, not today's work unit: a later correction can
    // move away from a project which is still disclosed by this exact replay.
    val legacySourceOnly =
      Seq("revision", "override", "effectiveProjectClient", "effectiveTaskId").forall(body.hcursor.downField(_).focus.isEmpty) &&
        body.hcursor.downField("pmClassification").focus.forall(_.isNull)
    fieldAllowed("projectClient") && (legacySourceOnly || fieldAllowed("effectiveProjectClient"))
  }
}

object OAuthWorkUnitWriteAdapter {
  final case class OAuthWorkUnitCreateRequest(
    userId: UserId,
    projectId: pl.iterators.epoca.project.domain.ProjectDomain.ProjectId,
    date: WorkUnitDate,
    duration: WorkUnitDuration,
    description: WorkUnitDescription,
    taskId: Option[WorkUnitTaskId]) {
    private[adapters] def toWorkUnitCreateRequest: WorkUnitCreateRequest =
      WorkUnitCreateRequest(
        projectId,
        date,
        Some(duration),
        description,
        taskId,
        isDraft = false,
        entryType = Some(pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitEntryType.Worklog),
        userId = Some(userId)
      )
  }
}
