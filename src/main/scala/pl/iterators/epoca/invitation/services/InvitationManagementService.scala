package pl.iterators.epoca.invitation.services

import cats.effect.IO
import io.circe.Json
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.{InvitationOnboardingRepository, InvitationRepository}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.EmailIdentityRepository
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Clock
import java.util.UUID
import scala.concurrent.ExecutionContext

class InvitationManagementService(
  transactor: PostgresIOTransactor,
  repository: InvitationOnboardingRepository,
  legacyRepository: InvitationRepository,
  emails: EmailIdentityRepository,
  guard: InvitationAccessGuard,
  secrets: InvitationSecretService,
  clock: Clock) {
  import InvitationManagementService._
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private case class CommandFailure(result: Result) extends RuntimeException

  private def execute(action: DBIO[Result]): IO[Result] = transactor.executeTransactionally(action).recover {
    case CommandFailure(result) => result
    case error if InvitationOnboardingRepository.conflict(error).contains(InvitationOnboardingRepository.PendingEmailConflict) =>
      InvitationAlreadyExists
  }
  def authorize(actor: UserId, slug: OrganizationSlug): IO[Boolean] =
    transactor.executeTransactionally(guard.resolve(actor, slug, Vector.empty).map(_.isDefined))

  def create(
    actor: UserId,
    slug: OrganizationSlug,
    requestId: UUID,
    body: Json
  ): IO[Result] = execute(createAction(actor, slug, requestId, body))

  def list(
    actor: UserId,
    slug: OrganizationSlug,
    pagination: pl.iterators.epoca.utils.db.PageSizeAndNumber,
    id: Option[InvitationId],
    enabled: Boolean
  ): IO[Result] = execute(listAction(actor, slug, pagination, id, enabled))

  private[invitation] def listAction(
    actor: UserId,
    slug: OrganizationSlug,
    pagination: pl.iterators.epoca.utils.db.PageSizeAndNumber,
    id: Option[InvitationId],
    enabled: Boolean
  ): DBIO[Result] =
    guard.resolve(actor, slug, Vector.empty).flatMap {
      case None => DBIO.successful(NotFound)
      case Some(access) =>
        for {
          rows <- id match {
                    case Some(value) => repository.findInOrganization(access.organizationId, value).map(_.toVector)
                    case None        => repository.page(access.organizationId, pagination)
                  }
          count <- if (id.isDefined) DBIO.successful(rows.size.toLong) else repository.count(access.organizationId)
          dtos <- DBIO.sequence(
                    rows.map(record => repository.attempts(record.invitation.id).map(InvitationManagementJson.admin(record, _, clock.instant())))
                  )
        } yield Ok(Json.obj("count" -> Json.fromLong(count), "results" -> Json.fromValues(dtos), "featureEnabled" -> Json.fromBoolean(enabled)), None)
    }
  def detail(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId
  ): IO[Result] = execute(detailAction(actor, slug, id))

  private[invitation] def detailAction(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId
  ): DBIO[Result] =
    guard.resolve(actor, slug, Vector.empty).flatMap {
      case None => DBIO.successful(NotFound)
      case Some(access) =>
        repository.findInOrganization(access.organizationId, id).flatMap {
          case Some(record) if record.invitation.organizationId == access.organizationId =>
            repository.attempts(id).map(attempts => Ok(InvitationManagementJson.admin(record, attempts, clock.instant()), None))
          case _ => DBIO.successful(NotFound)
        }
    }
  def patch(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    body: Json
  ): IO[Result] = execute(patchAction(actor, slug, id, body))
  def resend(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    requestId: UUID,
    body: Json
  ): IO[Result] = execute(resendAction(actor, slug, id, requestId, body))
  def cancel(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    expectedRevision: Long
  ): IO[Result] = execute(cancelAction(actor, slug, id, Some(expectedRevision)))

  private[invitation] def patchAction(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    body: Json
  ): DBIO[Result] =
    locked(actor, slug, id) {
      (
        before,
        access,
        resolution
      ) => editLocked(actor, before, access, resolution, body, send = false)
    }
  private[invitation] def resendAction(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    requestId: UUID,
    body: Json
  ): DBIO[Result] =
    locked(actor, slug, id) {
      (
        before,
        access,
        resolution
      ) =>
        val key = CommandKey(actor, access.organizationId, "Resend", requestId)
        // Hash the submitted command, independent of mutable invitation state, so lost-response retries remain replayable.
        val payload = Json.obj("invitationId" -> Json.fromString(id.toString), "body" -> canonicalJson(body))
        val hash    = Digest(secrets.hash(payload.noSpaces))
        repository.findCommand(key).flatMap {
          case Some(previous) if previous.payloadHash == hash => DBIO.successful(Ok(previous.result, None))
          case Some(_)                                        => DBIO.successful(IdempotencyConflict)
          case None =>
            editLocked(actor, before, access, resolution, body, send = true).flatMap {
              case result: Ok =>
                repository.insertCommand(Command(key, hash, id, result.dto)).flatMap {
                  case true  => DBIO.successful(result)
                  case false => DBIO.failed(CommandFailure(IdempotencyConflict))
                }
              case result => DBIO.successful(result)
            }
        }
    }

  private def canonicalJson(json: Json): Json = json.asObject match {
    case Some(fields) => Json.fromFields(fields.toVector.sortBy(_._1).map { case (key, value) => key -> canonicalJson(value) })
    case None         => json.asArray.map(values => Json.fromValues(values.map(canonicalJson))).getOrElse(json)
  }

  private def locked(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId
  )(
    operation: (Record, InvitationAccessGuard.Access, Resolution) => DBIO[Result]
  ): DBIO[Result] =
    repository.find(id).flatMap {
      case None => DBIO.successful(NotFound)
      case Some(hint) =>
        val email = hint.canonicalEmail
        for {
          _     <- email.map(emails.lock).getOrElse(legacyRepository.lockPhone(hint.invitation.identityValue))
          found <- email.map(emails.resolve).getOrElse(DBIO.successful(Resolution.Empty))
          auth  <- guard.resolve(actor, slug, candidates(found))
          result <- auth match {
                      case None => DBIO.successful(NotFound)
                      case Some(access) =>
                        repository.lockInvitation(id).flatMap {
                          case Some(record) if record.invitation.organizationId == access.organizationId =>
                            email
                              .map(emails.resolve)
                              .getOrElse(DBIO.successful(Resolution.Empty))
                              .flatMap(current => operation(record, access, current))
                          case _ => DBIO.successful(NotFound)
                        }
                    }
        } yield result
    }
  private def terminal(record: Record): Boolean = {
    val i = record.invitation
    i.acceptAt.isDefined || i.rejectAt.isDefined || i.archivedAt.isDefined
  }
  private def revise(
    record: Record,
    actor: UserId,
    now: java.time.Instant
  ): Record =
    record.copy(revision = record.revision + 1, invitation = record.invitation.copy(modifiedBy = actor, modifiedAt = InvitationModifiedAt(now)))

  private def save(
    before: Record,
    after: Record,
    actor: UserId,
    action: String,
    attempts: Vector[DeliveryAttempt],
    delivery: Option[Delivery]
  ): DBIO[Result] = {
    val now = after.invitation.modifiedAt
    val dto = InvitationManagementJson.admin(after, attempts, now)
    for {
      updated <- repository.update(after, before.revision, before.generation)
      _       <- if (updated) DBIO.successful(()) else DBIO.failed(CommandFailure(RevisionConflict))
      _ <- repository.appendAudit(
             Audit(
               InvitationAuditId(UUID.randomUUID()),
               after.invitation.id,
               Some(actor),
               action,
               now,
               Some(InvitationManagementJson.admin(before, attempts.filter(_.generation == before.generation), now)),
               Some(dto)
             )
           )
    } yield Ok(dto, delivery)
  }
  private def editLocked(
    actor: UserId,
    before: Record,
    access: InvitationAccessGuard.Access,
    resolution: Resolution,
    body: Json,
    send: Boolean
  ): DBIO[Result] = {
    val id = before.invitation.id
    if (terminal(before)) DBIO.successful(InvitationNotPending)
    else if (before.invitation.identityType != IdentityType.MailAddress)
      DBIO.successful(InvalidRequest("Only email invitations support onboarding"))
    else
      InvitationManagementJson.decodePatch(before, body) match {
        case Left(message) => DBIO.successful(InvalidRequest(message))
        case Right((revision, _)) if revision != before.revision || revision == Long.MaxValue || (send && before.generation == Long.MaxValue) =>
          DBIO.successful(RevisionConflict)
        case Right((_, request)) =>
          recipientError(resolution, access) match {
            case Some(error) => DBIO.successful(error)
            case None =>
              repository.attempts(id).flatMap { attempts =>
                val now   = clock.instant()
                val retry = attempts.map(a => InvitationManagementJson.retryAfter(a.startedAt, now)).maxOption.getOrElse(0L)
                if (send && retry > 0) DBIO.successful(RateLimited(retry))
                else {
                  val edited = revise(before, actor, now)
                    .copy(firstName = Some(request.firstName), lastName = Some(request.lastName), locale = Some(request.locale))
                  val roles = edited.copy(invitation = edited.invitation.copy(roles = Seq(request.role)))
                  if (!send) save(before, roles, actor, "Edited", attempts, None)
                  else {
                    val secret     = secrets.generate()
                    val generation = before.generation + 1
                    val after = roles.copy(
                      protocolVersion = 2,
                      generation = generation,
                      tokenHash = Some(Digest(secrets.hash(secret))),
                      invitation = roles.invitation
                        .copy(identityValue = UserIdentityValue(request.email), expireAt = Some(InvitationExpireAt(now.plusSeconds(7 * 86400))))
                    )
                    val attempt = DeliveryAttempt(InvitationDeliveryAttemptId(UUID.randomUUID()), id, generation, now, None, None, None)
                    repository
                      .insertAttempt(attempt)
                      .flatMap(_ => save(before, after, actor, "Resent", attempts :+ attempt, Some(Delivery(id, attempt.id, generation, secret))))
                  }
                }
              }
          }
      }
  }

  private[invitation] def cancelAction(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId,
    expectedRevision: Option[Long]
  ): DBIO[Result] =
    locked(actor, slug, id) {
      (
        before,
        _,
        _
      ) =>
        if (terminal(before)) DBIO.successful(InvitationNotPending)
        else if (expectedRevision.exists(_ != before.revision) || before.revision == Long.MaxValue) DBIO.successful(RevisionConflict)
        else {
          val now     = clock.instant()
          val revised = revise(before, actor, now)
          val after   = revised.copy(invitation = revised.invitation.copy(archivedAt = Some(InvitationArchivedAt(now))))
          repository.attempts(id).flatMap(attempts => save(before, after, actor, "Cancelled", attempts, None))
        }
    }

  private[invitation] def createLegacyAction(
    actor: UserId,
    slug: OrganizationSlug,
    request: OrganizationInvitationCreateService.OrganizationInvitationCreateRequest
  ): DBIO[Result] =
    if (request.email.isDefined == request.phoneNumber.isDefined) DBIO.successful(InvalidRequest("Exactly one email or phone number is required"))
    else {
      val email = request.email.map(pl.iterators.epoca.utils.db.EmailCanonicalization.canonical)
      val value = UserIdentityValue(email.orElse(request.phoneNumber.map(v => v: String)).get)
      if (value.isEmpty || value.contains('\u0000')) DBIO.successful(InvalidRequest("Identity must not be empty"))
      else
        for {
          _     <- email.map(emails.lock).getOrElse(legacyRepository.lockPhone(value))
          found <- email.map(emails.resolve).getOrElse(DBIO.successful(Resolution.Empty))
          ids   <- email.map(_ => DBIO.successful(candidates(found))).getOrElse(legacyRepository.phoneCandidates(value))
          auth  <- guard.resolve(actor, slug, ids)
          result <- auth match {
                      case None => DBIO.successful(NotFound)
                      case Some(access) =>
                        val phoneError =
                          if (email.isDefined) None
                          else if (ids.exists(id => !access.activeHumanUsers(id))) Some(IdentityConflict)
                          else Option.when(ids.exists(access.activeMembers))(AlreadyMember)
                        email.map(emails.resolve).getOrElse(DBIO.successful(Resolution.Empty)).flatMap { current =>
                          recipientError(current, access).orElse(phoneError) match {
                            case Some(error) => DBIO.successful(error)
                            case None =>
                              val pending = email
                                .map(address => repository.findPendingByEmail(access.organizationId, address).map(_.isDefined))
                                .getOrElse(legacyRepository.pendingPhone(access.organizationId, value))
                              pending.flatMap {
                                case true => DBIO.successful(InvitationAlreadyExists)
                                case false =>
                                  val now = clock.instant()
                                  val invitation = Invitation(
                                    InvitationId(UUID.randomUUID()),
                                    value,
                                    if (email.isDefined) IdentityType.MailAddress else IdentityType.Phone,
                                    request.roles.getOrElse(Nil),
                                    request.expireTimestamp,
                                    None,
                                    None,
                                    access.organizationId,
                                    InvitationCreatedAt(now),
                                    actor,
                                    InvitationModifiedAt(now),
                                    actor,
                                    None
                                  )
                                  val record = Record(invitation, 1, None, None, 0, 1, None, None, None, email)
                                  for {
                                    _ <- repository.insert(record)
                                    _ <- repository.appendAudit(
                                           Audit(
                                             InvitationAuditId(UUID.randomUUID()),
                                             invitation.id,
                                             Some(actor),
                                             "Created",
                                             now,
                                             None,
                                             Some(InvitationManagementJson.admin(record, Vector.empty, now))
                                           )
                                         )
                                  } yield LegacyCreated(invitation)
                              }
                          }
                        }
                    }
        } yield result
    }
  def createLegacy(
    actor: UserId,
    slug: OrganizationSlug,
    request: OrganizationInvitationCreateService.OrganizationInvitationCreateRequest
  ): IO[Result] = execute(createLegacyAction(actor, slug, request))
  def cancelLegacy(
    actor: UserId,
    slug: OrganizationSlug,
    id: InvitationId
  ): IO[Result] = execute(cancelAction(actor, slug, id, None))

  private def candidates(resolution: Resolution): Vector[UserId] = resolution match {
    case Resolution.Single(user, _) => Vector(user.id)
    case _                          => Vector.empty
  }
  private def recipientError(resolution: Resolution, access: InvitationAccessGuard.Access): Option[Result] = resolution match {
    case Resolution.Ambiguous                                                                                 => Some(IdentityConflict)
    case Resolution.Single(user, _) if user.archivedAt.isDefined || user.accountType != UserAccountType.Human => Some(IdentityConflict)
    case Resolution.Single(user, _) if access.activeMembers.contains(user.id)                                 => Some(AlreadyMember)
    case _                                                                                                    => None
  }

  /** The caller must execute this action in one transaction. Email reservation precedes all row locks. */
  private[invitation] def createAction(
    actor: UserId,
    slug: OrganizationSlug,
    requestId: UUID,
    body: Json
  ): DBIO[Result] =
    InvitationManagementJson.decodeCreate(body) match {
      case Left(message) => DBIO.successful(InvalidRequest(message))
      case Right(request) =>
        for {
          _      <- emails.lock(request.email)
          found  <- emails.resolve(request.email)
          access <- guard.resolve(actor, slug, candidates(found))
          result <- access match {
                      case None => DBIO.successful(NotFound)
                      case Some(auth) =>
                        val key  = CommandKey(actor, auth.organizationId, "Create", requestId)
                        val hash = Digest(secrets.hash(InvitationManagementJson.encodeCreate(request).noSpaces))
                        repository.findCommand(key).flatMap {
                          case Some(previous) if previous.payloadHash == hash => DBIO.successful(Ok(previous.result, None))
                          case Some(_)                                        => DBIO.successful(IdempotencyConflict)
                          case None =>
                            emails.resolve(request.email).flatMap { current =>
                              recipientError(current, auth) match {
                                case Some(error) => DBIO.successful(error)
                                case None =>
                                  repository.findPendingByEmail(auth.organizationId, request.email).flatMap {
                                    case Some(_) => DBIO.successful(InvitationAlreadyExists)
                                    case None =>
                                      val now    = clock.instant()
                                      val secret = secrets.generate()
                                      val invitation = Invitation(
                                        InvitationId(UUID.randomUUID()),
                                        UserIdentityValue(request.email),
                                        IdentityType.MailAddress,
                                        Seq(request.role),
                                        Some(InvitationExpireAt(now.plusSeconds(7 * 86400))),
                                        None,
                                        None,
                                        auth.organizationId,
                                        InvitationCreatedAt(now),
                                        actor,
                                        InvitationModifiedAt(now),
                                        actor,
                                        None
                                      )
                                      val record = Record(
                                        invitation,
                                        2,
                                        Some(request.firstName),
                                        Some(request.lastName),
                                        0,
                                        1,
                                        Some(Digest(secrets.hash(secret))),
                                        None,
                                        Some(request.locale),
                                        Some(request.email)
                                      )
                                      val attempt =
                                        DeliveryAttempt(InvitationDeliveryAttemptId(UUID.randomUUID()), invitation.id, 1, now, None, None, None)
                                      val dto = InvitationManagementJson.admin(record, Vector(attempt), now)
                                      for {
                                        _ <- repository.insert(record)
                                        _ <-
                                          repository.appendAudit(
                                            Audit(InvitationAuditId(UUID.randomUUID()), invitation.id, Some(actor), "Created", now, None, Some(dto))
                                          )
                                        _        <- repository.insertAttempt(attempt)
                                        inserted <- repository.insertCommand(Command(key, hash, invitation.id, dto))
                                        _        <- if (inserted) DBIO.successful(()) else DBIO.failed(CommandFailure(IdempotencyConflict))
                                      } yield Ok(dto, Some(Delivery(invitation.id, attempt.id, 1, secret)))
                                  }
                              }
                            }
                        }
                    }
        } yield result
    }
}
object InvitationManagementService {
  final case class LegacyCreated(invitation: Invitation) extends Result
  sealed trait Result
  case object NotFound                            extends Result
  case object AlreadyMember                       extends Result
  case object InvitationAlreadyExists             extends Result
  case object IdempotencyConflict                 extends Result
  case object IdentityConflict                    extends Result
  case object RevisionConflict                    extends Result
  case object InvitationNotPending                extends Result
  case class InvalidRequest(message: String)      extends Result
  case class RateLimited(retryAfterSeconds: Long) extends Result
  final case class Delivery(
    invitationId: InvitationId,
    attemptId: InvitationDeliveryAttemptId,
    generation: Long,
    secret: String) {
    override def toString: String = s"InvitationDelivery($invitationId,$attemptId,$generation,<redacted>)"
  }
  final case class Ok(dto: Json, delivery: Option[Delivery]) extends Result
}
