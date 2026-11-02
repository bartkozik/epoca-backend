package pl.iterators.epoca.workunit.routers

import cats.effect.unsafe.IORuntime
import io.circe.{Decoder, Encoder}
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{MalformedHeaderRejection, Route}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType, WorkUnitId, WorkUnitState}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain.WorkUnitIdempotencyKey
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyResult.{KeyConflict, Response, ResultExpired, VersionUnsupported}
import pl.iterators.epoca.workunit.idempotency.{WorkUnitIdempotencyResult, WorkUnitWriteOutcome}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.{WorkUnitCreateRequest, WorkUnitCreateResult}
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService.WorkUnitDeleteResult
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListResult
import pl.iterators.epoca.workunit.services.WorkUnitOrderUpsertService.{WorkUnitOrderUpsertRequest, WorkUnitOrderUpsertResult}
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.{WorkUnitUpdateRequest, WorkUnitUpdateResult}
import pl.iterators.epoca.workunit.services._

import java.util.UUID
import scala.util.Try

class WorkUnitRouter(
  workUnitCreateService: WorkUnitCreateService,
  workUnitListService: WorkUnitListService,
  workUnitUpdateService: WorkUnitUpdateService,
  workUnitDeleteService: WorkUnitDeleteService,
  workUnitOrderUpsertService: WorkUnitOrderUpsertService,
  workUnitIdempotencyService: WorkUnitIdempotencyService,
  workUnitWriteOutcomeMapper: WorkUnitWriteOutcomeMapper
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import WorkUnitRouter._

  private implicit val workUnitEntryTypeDecoder: Decoder[WorkUnitEntryType] = Decoder.decodeString.emap { value =>
    WorkUnitEntryType.withNameOption(value).toRight(s"$value should be one of ${WorkUnitEntryType.values.map(_.entryName).mkString(", ")}")
  }

  private implicit lazy val workUnitDtoEncoder: Encoder.AsObject[WorkUnitDto] = WorkUnitDto.encoder

  def authRoutes(auth: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(organizationsPath / Segment.as[OrganizationSlug] / workUnitPath) { organizationSlug =>
      (pathPrefix(orderPath) & put & pathEndOrSingleSlash & entity(as[WorkUnitOrderUpsertRequest])) { request =>
        complete(workUnitOrderUpsertService.upsert(auth, organizationSlug, request)) {
          case WorkUnitOrderUpsertResult.Ok(order)              => StatusCodes.Created -> order
          case WorkUnitOrderUpsertResult.OrganizationNotFound   => StatusCodes.NotFound
          case WorkUnitOrderUpsertResult.NoAccessToOrganization => StatusCodes.NotFound
        }
      } ~
        (post & pathEndOrSingleSlash & entity(as[WorkUnitCreateRequest])) { request =>
          optionalHeaderValueByName(idempotencyKeyHeaderName) {
            case None =>
              complete(workUnitCreateService.create(auth, organizationSlug, request)) {
                case WorkUnitCreateResult.Created(workUnitDto)   => StatusCodes.Created -> workUnitDto
                case WorkUnitCreateResult.OrganizationNotFound   => StatusCodes.NotFound
                case WorkUnitCreateResult.NoAccessToOrganization => StatusCodes.NotFound
                case WorkUnitCreateResult.ProjectNotFound        => StatusCodes.NotFound
                case WorkUnitCreateResult.NoAccessToProject      => StatusCodes.Forbidden
                case WorkUnitCreateResult.NonPositiveDuration =>
                  legacyCreateProblem(WorkUnitCreateResult.NonPositiveDuration)
                case WorkUnitCreateResult.NonNeutralNoteDuration =>
                  legacyCreateProblem(WorkUnitCreateResult.NonNeutralNoteDuration)
                case WorkUnitCreateResult.FutureWorklogDate =>
                  legacyCreateProblem(WorkUnitCreateResult.FutureWorklogDate)
                case WorkUnitCreateResult.NoAccessToTargetUser =>
                  legacyCreateProblem(WorkUnitCreateResult.NoAccessToTargetUser)
                case WorkUnitCreateResult.TargetUserNotAssignedToProject =>
                  legacyCreateProblem(WorkUnitCreateResult.TargetUserNotAssignedToProject)
                case WorkUnitCreateResult.TargetUserNotFound =>
                  legacyCreateProblem(WorkUnitCreateResult.TargetUserNotFound)
              }
            case Some(rawKey) =>
              parseIdempotencyKey(rawKey) match {
                case Left(message) => reject(MalformedHeaderRejection(idempotencyKeyHeaderName, message))
                case Right(key) =>
                  complete(
                    workUnitIdempotencyService.create(auth, organizationSlug, key, request, tracingContext.correlationId)(
                      workUnitCreateService.createAction(auth, organizationSlug, request)
                    )
                  )(idempotencyResponse)
              }
          }
        } ~ (get & pathEndOrSingleSlash & pagination & parameters(
          "id".as[WorkUnitId].?,
          "dateFrom".as[WorkUnitDate].?,
          "dateTo".as[WorkUnitDate].?,
          "projectId".as[ProjectId].*,
          "userId".as[UserId].*,
          "state".as[WorkUnitState].*,
          "clientId".as[ClientId].*,
          "includeArchived".as[Boolean].?(false),
          "shouldReturnDraftIds".as[Boolean].?(false)
        )) {
          (
            pagination,
            id,
            dateFrom,
            dateTo,
            projectIds,
            userIds,
            states,
            clientIds,
            includeArchived,
            shouldReturnDraftIds
          ) =>
            val filters = WorkUnitFilters(
              pageSizeAndNumber = pagination,
              id = id,
              dateFrom = dateFrom,
              dateTo = dateTo,
              projectIds = Option.when(projectIds.nonEmpty)(projectIds.toSeq),
              userIds = Option.when(userIds.nonEmpty)(userIds.toSeq),
              states = Option.when(states.nonEmpty)(states.toSeq),
              clientIds = Option.when(clientIds.nonEmpty)(clientIds.toSeq),
              includeArchived = includeArchived
            )
            complete(workUnitListService.listWithOrder(auth, organizationSlug, shouldReturnDraftIds, filters)) {
              case WorkUnitListResult.Ok(data)               => StatusCodes.OK -> data
              case WorkUnitListResult.OrganizationNotFound   => StatusCodes.NotFound
              case WorkUnitListResult.NoAccessToOrganization => StatusCodes.NotFound
              case WorkUnitListResult.OverrideForbidden      => StatusCodes.Forbidden
            }
        } ~ pathPrefix(JavaUUID.as[WorkUnitId]) { id =>
          (patch & pathEndOrSingleSlash & entity(as[WorkUnitUpdateRequest])) { request =>
            optionalHeaderValueByName(idempotencyKeyHeaderName) {
              case None =>
                complete(workUnitUpdateService.update(auth, organizationSlug, id, request)) {
                  case WorkUnitUpdateResult.OrganizationNotFound   => StatusCodes.NotFound
                  case WorkUnitUpdateResult.NoAccessToOrganization => StatusCodes.NotFound
                  case WorkUnitUpdateResult.NonPositiveDuration =>
                    legacyUpdateProblem(WorkUnitUpdateResult.NonPositiveDuration)
                  case WorkUnitUpdateResult.NonNeutralNoteDuration =>
                    legacyUpdateProblem(WorkUnitUpdateResult.NonNeutralNoteDuration)
                  case WorkUnitUpdateResult.FutureWorklogDate =>
                    legacyUpdateProblem(WorkUnitUpdateResult.FutureWorklogDate)
                  case WorkUnitUpdateResult.WorkUnitNotFound     => StatusCodes.NotFound
                  case WorkUnitUpdateResult.NoAccessToWorkUnit   => StatusCodes.Forbidden
                  case WorkUnitUpdateResult.ProjectNotFound      => StatusCodes.NotFound
                  case WorkUnitUpdateResult.WorkUnitInactive     => StatusCodes.Forbidden -> "Work unit can't be edited anymore"
                  case WorkUnitUpdateResult.NoAccessToProject    => StatusCodes.Forbidden
                  case WorkUnitUpdateResult.Updated(workUnitDto) => StatusCodes.OK        -> workUnitDto
                }
              case Some(rawKey) =>
                parseIdempotencyKey(rawKey) match {
                  case Left(message) => reject(MalformedHeaderRejection(idempotencyKeyHeaderName, message))
                  case Right(key) =>
                    complete(
                      workUnitIdempotencyService.update(auth, organizationSlug, id, key, request, tracingContext.correlationId)(
                        workUnitUpdateService.updateAction(auth, organizationSlug, id, request)
                      )
                    )(idempotencyResponse)
                }
            }
          } ~ (delete & pathEndOrSingleSlash) {
            complete(workUnitDeleteService.delete(auth, organizationSlug, id)) {
              case WorkUnitDeleteResult.OrganizationNotFound   => StatusCodes.NotFound
              case WorkUnitDeleteResult.NoAccessToOrganization => StatusCodes.NotFound
              case WorkUnitDeleteResult.WorkUnitNotFound       => StatusCodes.NotFound
              case WorkUnitDeleteResult.NoAccessToWorkUnit     => StatusCodes.Forbidden
              case WorkUnitDeleteResult.Deleted                => StatusCodes.NoContent
            }
          }
        }
    }

  private def legacyCreateProblem(result: WorkUnitCreateResult)(implicit tracingContext: TracingContext): ToResponseMarshallable =
    writeOutcomeResponse(workUnitWriteOutcomeMapper.create(result, tracingContext.correlationId))

  private def legacyUpdateProblem(result: WorkUnitUpdateResult)(implicit tracingContext: TracingContext): ToResponseMarshallable =
    writeOutcomeResponse(workUnitWriteOutcomeMapper.update(result, tracingContext.correlationId))

  private def writeOutcomeResponse(outcome: WorkUnitWriteOutcome, headers: Seq[HttpHeader] = Nil): ToResponseMarshallable =
    HttpResponse(
      status = persistedStatus(outcome.status),
      headers = headers,
      entity = HttpEntity(ContentTypes.`application/json`, outcome.body.noSpaces)
    )

  private def idempotencyResponse(result: WorkUnitIdempotencyResult)(implicit tracingContext: TracingContext): ToResponseMarshallable =
    result match {
      case Response(outcome, replayed) =>
        writeOutcomeResponse(outcome, Option.when(replayed)(RawHeader(idempotencyReplayedHeaderName, "true")).toList)
      case KeyConflict =>
        error(StatusCodes.Conflict, "work-unit:idempotency-key-conflict", "Idempotency key conflict")
      case VersionUnsupported =>
        error(StatusCodes.Conflict, "work-unit:idempotency-version-unsupported", "Idempotency version unsupported")
      case ResultExpired =>
        error(StatusCodes.Gone, "work-unit:idempotency-result-expired", "Idempotency result expired")
    }

  private def persistedStatus(value: Int): StatusCode =
    StatusCodes.getForKey(value).getOrElse {
      if (value >= 100 && value <= 599) StatusCodes.custom(value, s"Stored status $value")
      else throw new IllegalStateException(s"Stored idempotency response has invalid HTTP status $value")
    }
}

object WorkUnitRouter {
  private final val organizationsPath             = "organizations"
  private final val workUnitPath                  = "work-units"
  private final val orderPath                     = "order"
  private final val idempotencyKeyHeaderName      = "Idempotency-Key"
  private final val idempotencyReplayedHeaderName = "Idempotency-Replayed"
  private def parseIdempotencyKey(rawValue: String): Either[String, WorkUnitIdempotencyKey] =
    Try(UUID.fromString(rawValue)).toEither
      .flatMap { uuid =>
        Either.cond(uuid.toString.equalsIgnoreCase(rawValue), WorkUnitIdempotencyKey(uuid), "Expected a canonical UUID")
      }
      .left
      .map(_ => "Expected a canonical UUID")
}
