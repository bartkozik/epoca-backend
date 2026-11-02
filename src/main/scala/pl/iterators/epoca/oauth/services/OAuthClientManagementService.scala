package pl.iterators.epoca.oauth.services

import cats.effect.IO
import io.circe.{ACursor, Decoder, DecodingFailure, Encoder, HCursor, Json, JsonObject}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.{OAuthClientFilters, OAuthClientMutationResult, OAuthClientSnapshot}
import pl.iterators.epoca.oauth.repositories.tables.OAuthAuditEventRow
import pl.iterators.epoca.oauth.repositories.{OAuthAuditRepository, OAuthClientRepository}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientManagementResult._
import pl.iterators.epoca.oauth.services.OAuthClientManagementService._
import pl.iterators.epoca.oauth.services.OAuthGrantabilityService.{OAuthGrantability, OAuthGrantabilityError}
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug, OrganizationUserId}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{ServiceAccountRepository, UserRepository}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.transactor.IsolationLevel

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Try

class OAuthClientManagementService(
  transactor: PostgresIOTransactor,
  authoritativeTime: OAuthAuthoritativeTime,
  grantabilityService: OAuthGrantabilityService,
  serviceAccountRepository: ServiceAccountRepository,
  oauthClientRepository: OAuthClientRepository,
  oauthAuditRepository: OAuthAuditRepository,
  userRepository: UserRepository,
  secretService: OAuthSecretService
)(implicit ec: ExecutionContext) {

  def create(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    request: OAuthClientCreateRequest,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): IO[OAuthClientManagementResult[OAuthClientSecretView]] = {
    val credentials = secretService.generateCredentials()
    val prepared = OAuthClientPreparedCreate(
      credentials = credentials,
      secretHash = secretService.hash(credentials.clientSecret),
      clientId = OAuthClientId(UUID.randomUUID()),
      serviceAccountUserId = UserId(UUID.randomUUID()),
      membershipId = OrganizationUserId.random,
      auditEventId = OAuthAuditEventId(UUID.randomUUID())
    )

    executeTransactionally(createPreparedDBIO(auth, organizationSlug, request, prepared, correlationId))
  }

  private[oauth] def createPreparedDBIO(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    request: OAuthClientCreateRequest,
    prepared: OAuthClientPreparedCreate,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): DBIO[OAuthClientManagementResult[OAuthClientSecretView]] =
    grantabilityService.resolve(auth, organizationSlug).flatMap {
      case Left(error) => DBIO.successful(fromGrantabilityError(error))
      case Right(grantability) =>
        validateCreateRequest(request, grantability) match {
          case Left(result) => DBIO.successful(result)
          case Right(name) =>
            authoritativeTime.now.flatMap { now =>
              buildPreparedCreateMutation(auth, request, prepared, grantability, name, now, correlationId) match {
                case Left(result)    => DBIO.successful(result)
                case Right(mutation) => persistPreparedCreateDBIO(mutation, now)
              }
            }
        }
    }

  private[oauth] def prepareCreateMutation(
    auth: AuthContext,
    request: OAuthClientCreateRequest,
    prepared: OAuthClientPreparedCreate,
    grantability: OAuthGrantability,
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): Either[OAuthClientManagementResult[Nothing], OAuthClientPreparedMutation] =
    validateCreateRequest(request, grantability).flatMap { name =>
      buildPreparedCreateMutation(auth, request, prepared, grantability, name, now, correlationId)
    }

  private def validateCreateRequest(request: OAuthClientCreateRequest, grantability: OAuthGrantability)
    : Either[OAuthClientManagementResult[Nothing], String] =
    normalizedName(request.name) match {
      case None => Left(InvalidPolicy)
      case Some(name) =>
        grantabilityService.validateDelegation(grantability, request.scopes, request.projectAccess) match {
          case Left(error) => Left(fromGrantabilityError(error))
          case Right(_)    => Right(name)
        }
    }

  private def buildPreparedCreateMutation(
    auth: AuthContext,
    request: OAuthClientCreateRequest,
    prepared: OAuthClientPreparedCreate,
    grantability: OAuthGrantability,
    normalizedName: String,
    now: Instant,
    correlationId: Option[OAuthAuditCorrelationId]
  ): Either[OAuthClientManagementResult[Nothing], OAuthClientPreparedMutation] =
    validatedExpiration(request.expiresAt, now).map { expiresAt =>
      val identity = newServiceAccount(prepared.serviceAccountUserId, normalizedName, now)
      val membership =
        OrganizationUser(prepared.membershipId, grantability.organization.id, prepared.serviceAccountUserId, roles = Nil, archivedAt = None)
      val snapshot = newClient(
        prepared.clientId,
        prepared.credentials.clientId,
        grantability,
        prepared.serviceAccountUserId,
        normalizedName,
        prepared.secretHash,
        request.scopes,
        request.projectAccess,
        auth.id,
        now,
        expiresAt
      )
      val audit = managementAudit(prepared.auditEventId, now, snapshot, auth.id, OAuthAuditEventType.Create, correlationId, createMetadata(snapshot))
      OAuthClientPreparedMutation(
        identity,
        membership,
        snapshot,
        audit,
        OAuthClientSecretView(toView(snapshot, now, Map.empty), prepared.credentials.clientSecret.toString)
      )
    }

  private[oauth] def insertPreparedClientRowDBIO(mutation: OAuthClientPreparedMutation): DBIO[Int] =
    oauthClientRepository.insertPreparedClientRow(mutation.snapshot.client)

  private[oauth] def insertPreparedScopeRowDBIO(mutation: OAuthClientPreparedMutation, scope: OAuthScope): DBIO[Int] =
    oauthClientRepository.insertPreparedScopeRow(mutation.snapshot.client.id, scope)

  private[oauth] def insertPreparedProjectRowDBIO(
    mutation: OAuthClientPreparedMutation,
    projectId: pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
  ): DBIO[Int] =
    oauthClientRepository.insertPreparedProjectRow(mutation.snapshot.client.id, projectId)

  private[oauth] def insertPreparedAuditDBIO(mutation: OAuthClientPreparedMutation): DBIO[Int] =
    oauthAuditRepository.insertPrepared(mutation.audit)

  private def persistPreparedCreateDBIO(mutation: OAuthClientPreparedMutation, now: Instant)
    : DBIO[OAuthClientManagementResult[OAuthClientSecretView]] =
    for {
      _        <- serviceAccountRepository.insert(mutation.identity, mutation.membership)
      inserted <- oauthClientRepository.insert(mutation.snapshot)
      _        <- oauthAuditRepository.insert(mutation.audit)
      view     <- assembleClientView(inserted, now, userRepository)
    } yield Success(mutation.secretView.copy(client = view))

  def list(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    status: Option[OAuthClientEffectiveStatus],
    pagination: Option[PageSizeAndNumber]
  ): IO[OAuthClientManagementResult[PaginatedResult[OAuthClientView]]] =
    executeRepeatableRead {
      grantabilityService.resolve(auth, organizationSlug).flatMap {
        case Left(error) => DBIO.successful(fromGrantabilityError(error))
        case Right(grantability) =>
          val filters = OAuthClientFilters(grantability.organization.id, status, pagination)
          authoritativeTime.now.flatMap { now =>
            for {
              snapshots <- oauthClientRepository.list(filters, now)
              count     <- oauthClientRepository.count(filters, now)
              views     <- assembleClientViews(snapshots, now, userRepository)
            } yield Success(PaginatedResult(count, views))
          }
      }
    }

  def get(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId
  ): IO[OAuthClientManagementResult[OAuthClientView]] =
    execute {
      withVisibleClient(auth, organizationSlug, clientId) { (_, snapshot) =>
        for {
          now  <- authoritativeTime.now
          view <- assembleClientView(snapshot, now, userRepository)
        } yield Success(view)
      }
    }

  def update(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    request: OAuthClientUpdateRequest,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): IO[OAuthClientManagementResult[OAuthClientView]] =
    if (request.name.isEmpty && request.scopes.isEmpty && request.projectAccess.isEmpty && request.expiresAt == OAuthExpirationPatch.Unchanged)
      IO.pure(InvalidPolicy)
    else
      executeTransactionally {
        withVisibleClient(auth, organizationSlug, clientId) { (grantability, visible) =>
          oauthClientRepository.lock(visible.client.id).flatMap {
            case None                                                                           => DBIO.successful(ClientNotFound)
            case Some(current) if current.client.organizationId != grantability.organization.id => DBIO.successful(ClientNotFound)
            case Some(current) =>
              authoritativeTime.now.flatMap { now =>
                OAuthClientEffectiveStatus.resolve(current.client.status, current.client.expiresAt, now) match {
                  case OAuthClientEffectiveStatus.Revoked => DBIO.successful(NotActive)
                  case OAuthClientEffectiveStatus.Active | OAuthClientEffectiveStatus.Expired
                      if current.client.version.asInstanceOf[Long] != request.expectedVersion =>
                    DBIO.successful(StaleVersion(current.client.version.asInstanceOf[Long]))
                  case OAuthClientEffectiveStatus.Active | OAuthClientEffectiveStatus.Expired =>
                    val maybeName = request.name.fold(Option(current.client.name.toString))(normalizedName)
                    maybeName match {
                      case None => DBIO.successful(InvalidPolicy)
                      case Some(name) =>
                        val scopes = request.scopes.getOrElse(current.scopes)
                        val access = request.projectAccess.getOrElse(current.projectAccess)
                        grantabilityService.validateDelegation(grantability, scopes, access) match {
                          case Left(error) => DBIO.successful(fromGrantabilityError(error))
                          case Right(_) =>
                            val canonicalPatch = request.expiresAt match {
                              case OAuthExpirationPatch.Unchanged  => Some(OAuthExpirationPatch.Unchanged)
                              case OAuthExpirationPatch.Clear      => Some(OAuthExpirationPatch.Clear)
                              case OAuthExpirationPatch.Set(value) => canonicalExpiration(value).map(OAuthExpirationPatch.Set.apply)
                            }
                            canonicalPatch match {
                              case None => DBIO.successful(InvalidExpiration)
                              case Some(patch) =>
                                val expiresAt = patch match {
                                  case OAuthExpirationPatch.Unchanged  => current.client.expiresAt
                                  case OAuthExpirationPatch.Clear      => None
                                  case OAuthExpirationPatch.Set(value) => Some(value)
                                }
                                val invalidExpiration = patch match {
                                  case OAuthExpirationPatch.Set(value)                             => !value.asInstanceOf[Instant].isAfter(now)
                                  case OAuthExpirationPatch.Unchanged | OAuthExpirationPatch.Clear => false
                                }
                                if (invalidExpiration)
                                  DBIO.successful(InvalidExpiration)
                                else
                                  oauthClientRepository
                                    .updateAtVersion(
                                      current.client.id,
                                      request.expectedVersion,
                                      OAuthClientName(name),
                                      scopes,
                                      access,
                                      expiresAt,
                                      now,
                                      auth.id
                                    )
                                    .flatMap {
                                      case OAuthClientMutationResult.Stale(currentVersion) => DBIO.successful(StaleVersion(currentVersion))
                                      case OAuthClientMutationResult.Applied(updated) if updated.client.version == current.client.version =>
                                        assembleClientView(updated, now, userRepository).map(Success.apply)
                                      case OAuthClientMutationResult.Applied(updated) =>
                                        for {
                                          _ <-
                                            if (updated.client.name != current.client.name)
                                              serviceAccountRepository.rename(updated.client.serviceAccountUserId, UserLastName(name), now)
                                            else DBIO.successful(())
                                          _ <- oauthAuditRepository.insert(
                                                 managementAudit(
                                                   OAuthAuditEventId(UUID.randomUUID()),
                                                   now,
                                                   updated,
                                                   auth.id,
                                                   OAuthAuditEventType.Update,
                                                   correlationId,
                                                   updateMetadata(current, updated)
                                                 )
                                               )
                                          view <- assembleClientView(updated, now, userRepository)
                                        } yield Success(view)
                                    }
                            }
                        }
                    }
                }
              }
          }
        }
      }

  def rotateSecret(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    request: OAuthClientVersionRequest,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): IO[OAuthClientManagementResult[OAuthClientSecretView]] = {
    val credentials = secretService.generateCredentials()
    val hash        = secretService.hash(credentials.clientSecret)
    executeTransactionally {
      withVisibleClient(auth, organizationSlug, clientId) { (grantability, visible) =>
        oauthClientRepository.lock(visible.client.id).flatMap {
          case None                                                                           => DBIO.successful(ClientNotFound)
          case Some(current) if current.client.organizationId != grantability.organization.id => DBIO.successful(ClientNotFound)
          case Some(current) =>
            authoritativeTime.now.flatMap { now =>
              OAuthClientEffectiveStatus.resolve(current.client.status, current.client.expiresAt, now) match {
                case OAuthClientEffectiveStatus.Expired | OAuthClientEffectiveStatus.Revoked => DBIO.successful(NotActive)
                case OAuthClientEffectiveStatus.Active =>
                  oauthClientRepository.rotateSecretAtVersion(current.client.id, request.expectedVersion, hash.toString, now, auth.id).flatMap {
                    case OAuthClientMutationResult.Stale(currentVersion) => DBIO.successful(StaleVersion(currentVersion))
                    case OAuthClientMutationResult.Applied(updated) =>
                      for {
                        _ <- oauthAuditRepository.insert(
                               managementAudit(
                                 OAuthAuditEventId(UUID.randomUUID()),
                                 now,
                                 updated,
                                 auth.id,
                                 OAuthAuditEventType.Rotate,
                                 correlationId,
                                 rotateMetadata(updated)
                               )
                             )
                        view <- assembleClientView(updated, now, userRepository)
                      } yield Success(OAuthClientSecretView(view, credentials.clientSecret.toString))
                  }
              }
            }
        }
      }
    }
  }

  def revoke(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    request: OAuthClientVersionRequest,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): IO[OAuthClientManagementResult[OAuthClientView]] =
    executeTransactionally(revokePreparedDBIO(auth, organizationSlug, clientId, request, correlationId))

  private[oauth] def revokePreparedDBIO(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    request: OAuthClientVersionRequest,
    correlationId: Option[OAuthAuditCorrelationId] = None
  ): DBIO[OAuthClientManagementResult[OAuthClientView]] =
    revokePreparedDBIOWithBoundary(auth, organizationSlug, clientId, request, correlationId, DBIO.successful(()))

  private[oauth] def revokePreparedDBIOWithBoundary(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    request: OAuthClientVersionRequest,
    correlationId: Option[OAuthAuditCorrelationId],
    afterClientRevoke: DBIO[Unit]
  ): DBIO[OAuthClientManagementResult[OAuthClientView]] =
    withVisibleClient(auth, organizationSlug, clientId) { (grantability, visible) =>
      oauthClientRepository.lock(visible.client.id).flatMap {
        case None                                                                           => DBIO.successful(ClientNotFound)
        case Some(current) if current.client.organizationId != grantability.organization.id => DBIO.successful(ClientNotFound)
        case Some(current) =>
          authoritativeTime.now.flatMap { now =>
            OAuthClientEffectiveStatus.resolve(current.client.status, current.client.expiresAt, now) match {
              case OAuthClientEffectiveStatus.Revoked => DBIO.successful(NotActive)
              case OAuthClientEffectiveStatus.Active | OAuthClientEffectiveStatus.Expired =>
                oauthClientRepository.revokeAtVersion(current.client.id, request.expectedVersion, now, auth.id).flatMap {
                  case OAuthClientMutationResult.Stale(currentVersion) => DBIO.successful(StaleVersion(currentVersion))
                  case OAuthClientMutationResult.Applied(updated) =>
                    for {
                      _ <- afterClientRevoke
                      _ <- serviceAccountRepository.archive(updated.client.serviceAccountUserId, updated.client.organizationId, now)
                      _ <- oauthAuditRepository.insert(
                             managementAudit(
                               OAuthAuditEventId(UUID.randomUUID()),
                               now,
                               updated,
                               auth.id,
                               OAuthAuditEventType.Revoke,
                               correlationId,
                               revokeMetadata(updated)
                             )
                           )
                      view <- assembleClientView(updated, now, userRepository)
                    } yield Success(view)
                }
            }
          }
      }
    }

  private[oauth] def revokeOwnedPreparedDBIO(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    ownedClientId: OAuthClientId,
    correlationId: OAuthAuditCorrelationId,
    afterClientRevoke: DBIO[Unit] = DBIO.successful(())
  ): DBIO[OAuthClientManagementResult[OAuthClientView]] =
    oauthClientRepository.lock(ownedClientId).flatMap {
      case Some(current) if current.client.status == OAuthClientStatus.Active =>
        revokePreparedDBIOWithBoundary(
          auth,
          organizationSlug,
          current.client.clientId,
          OAuthClientVersionRequest(current.client.version.asInstanceOf[Long]),
          Some(correlationId),
          afterClientRevoke
        )
      case Some(_) => DBIO.successful(NotActive)
      case None    => DBIO.successful(ClientNotFound)
    }

  private[oauth] def reconcileOwnedRevokeDBIO(
    ownedClientId: OAuthClientId,
    organizationId: OrganizationId,
    correlationId: OAuthAuditCorrelationId
  ): DBIO[Boolean] =
    oauthClientRepository.lock(ownedClientId).flatMap {
      case Some(current) if current.client.organizationId == organizationId && current.client.status == OAuthClientStatus.Revoked =>
        for {
          account    <- serviceAccountRepository.findById(current.client.serviceAccountUserId, forUpdate = true)
          membership <- serviceAccountRepository.findMembership(current.client.serviceAccountUserId, organizationId)
          audits     <- oauthAuditRepository.list(OAuthAuditEventFilters(ownedClientId))
        } yield {
          val matching = audits.filter(row =>
            row.eventType == OAuthAuditEventType.Revoke &&
              row.outcome == OAuthAuditOutcome.Success &&
              row.correlationId.contains(correlationId)
          )
          account.exists(_.archivedAt.nonEmpty) && membership.exists(_.archivedAt.nonEmpty) && matching.size == 1
        }
      case _ => DBIO.successful(false)
    }

  private[oauth] def verifyOwnedTerminalRevokeDBIO(ownedClientId: OAuthClientId, organizationId: OrganizationId): DBIO[Boolean] =
    oauthClientRepository.lock(ownedClientId).flatMap {
      case Some(current) if current.client.organizationId == organizationId && current.client.status == OAuthClientStatus.Revoked =>
        for {
          account    <- serviceAccountRepository.findById(current.client.serviceAccountUserId, forUpdate = true)
          membership <- serviceAccountRepository.findMembershipForUpdate(current.client.serviceAccountUserId, organizationId)
          audits     <- oauthAuditRepository.listForUpdate(OAuthAuditEventFilters(ownedClientId))
        } yield {
          val successfulRevokes = audits.filter(row => row.eventType == OAuthAuditEventType.Revoke && row.outcome == OAuthAuditOutcome.Success)
          account.exists(_.archivedAt.nonEmpty) && membership.exists(_.archivedAt.nonEmpty) && successfulRevokes.size == 1
        }
      case _ => DBIO.successful(false)
    }

  def listAuditEvents(
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId,
    pagination: Option[PageSizeAndNumber]
  ): IO[OAuthClientManagementResult[PaginatedResult[OAuthAuditEventView]]] =
    executeRepeatableRead {
      withVisibleClient(auth, organizationSlug, clientId) { (_, snapshot) =>
        val filters = OAuthAuditEventFilters(snapshot.client.id, pagination)
        for {
          events <- oauthAuditRepository.list(filters)
          count  <- oauthAuditRepository.count(filters)
          _      <- authoritativeTime.now
          views  <- assembleAuditViews(events, snapshot, userRepository)
        } yield Success(PaginatedResult(count, views))
      }
    }

  private def withVisibleClient[A](
    auth: AuthContext,
    organizationSlug: OrganizationSlug,
    clientId: OAuthClientPublicId
  )(
    action: (OAuthGrantability, OAuthClientSnapshot) => DBIO[OAuthClientManagementResult[A]]
  ): DBIO[OAuthClientManagementResult[A]] =
    grantabilityService.resolve(auth, organizationSlug).flatMap {
      case Left(error) => DBIO.successful(fromGrantabilityError(error))
      case Right(grantability) =>
        oauthClientRepository.findPublic(clientId).flatMap {
          case Some(snapshot) if snapshot.client.organizationId == grantability.organization.id => action(grantability, snapshot)
          case _                                                                                => DBIO.successful(ClientNotFound)
        }
    }

  private def execute[A](action: DBIO[OAuthClientManagementResult[A]]): IO[OAuthClientManagementResult[A]] =
    transactor.execute(action).attempt.map(mapExecution[A])

  private def executeTransactionally[A](action: DBIO[OAuthClientManagementResult[A]]): IO[OAuthClientManagementResult[A]] =
    transactor.executeTransactionally(action).attempt.map(mapExecution[A])

  private def executeRepeatableRead[A](action: DBIO[OAuthClientManagementResult[A]]): IO[OAuthClientManagementResult[A]] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead)(action).attempt.map(mapExecution[A])

  private def mapExecution[A](result: Either[Throwable, OAuthClientManagementResult[A]]): OAuthClientManagementResult[A] =
    result match {
      case Right(value)                                       => value
      case Left(_: OAuthClientRepository.OAuthClientRevoked)  => NotActive
      case Left(error: SQLException) if isNameConflict(error) => NameConflict
      case Left(_: SQLException)                              => PersistenceUnavailable
      case Left(_: IllegalArgumentException)                  => InvalidPolicy
      case Left(_)                                            => PersistenceUnavailable
    }

  private def isNameConflict(error: SQLException): Boolean = {
    @annotation.tailrec
    def loop(current: SQLException): Boolean = {
      val message = Option(current.getMessage).getOrElse("")
      if (message.contains(NameConstraint)) true
      else
        Option(current.getNextException) match {
          case Some(next) => loop(next)
          case None       => false
        }
    }
    loop(error)
  }
}

object OAuthClientManagementService {
  private val NameConstraint             = "oauth_client_organization_id_name_key"
  private val MinimumExpiration          = Instant.parse("0001-01-01T00:00:00Z")
  private val MaximumExpirationExclusive = Instant.parse("+10000-01-01T00:00:00Z")
  private val ClosedAuditTimestamp =
    raw"""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$$""".r.pattern

  private[oauth] final case class OAuthClientPreparedCreate(
    credentials: OAuthClientCredentials,
    secretHash: OAuthClientSecretHash,
    clientId: OAuthClientId,
    serviceAccountUserId: UserId,
    membershipId: OrganizationUserId,
    auditEventId: OAuthAuditEventId) {
    override def toString: String =
      s"OAuthClientPreparedCreate(clientId=${credentials.clientId},clientSecret=<redacted>,secretHash=<redacted>,internalIds=<redacted>)"
  }

  private[oauth] final case class OAuthClientPreparedMutation(
    identity: User,
    membership: OrganizationUser,
    snapshot: OAuthClientSnapshot,
    audit: NewOAuthAuditEvent,
    secretView: OAuthClientSecretView) {
    override def toString: String =
      s"OAuthClientPreparedMutation(clientId=${snapshot.client.clientId},secret=<redacted>,internalIds=<redacted>)"
  }

  final case class OAuthClientCreateRequest(
    name: String,
    scopes: Set[OAuthScope],
    projectAccess: OAuthProjectAccess,
    expiresAt: Option[OAuthClientExpiresAt] = None)
  object OAuthClientCreateRequest {
    implicit val decoder: Decoder[OAuthClientCreateRequest] = Decoder.instance { cursor =>
      for {
        name          <- cursor.get[String]("name")
        scopes        <- cursor.get[Set[OAuthScope]]("scopes")
        projectAccess <- cursor.get[OAuthProjectAccess]("projectAccess")
        expiresAt     <- decodeCreateExpiration(cursor)
      } yield OAuthClientCreateRequest(name, scopes, projectAccess, expiresAt)
    }

    implicit val encoder: Encoder.AsObject[OAuthClientCreateRequest] = Encoder.AsObject.instance { request =>
      JsonObject(
        "name"          -> Json.fromString(request.name),
        "scopes"        -> scopesJson(request.scopes),
        "projectAccess" -> Encoder[OAuthProjectAccess].apply(request.projectAccess),
        "expiresAt"     -> expirationJson(request.expiresAt)
      )
    }
  }

  sealed trait OAuthExpirationPatch
  object OAuthExpirationPatch {
    case object Unchanged                             extends OAuthExpirationPatch
    case object Clear                                 extends OAuthExpirationPatch
    final case class Set(value: OAuthClientExpiresAt) extends OAuthExpirationPatch
  }

  final case class OAuthClientUpdateRequest(
    expectedVersion: Long,
    name: Option[String],
    scopes: Option[Set[OAuthScope]],
    projectAccess: Option[OAuthProjectAccess],
    expiresAt: OAuthExpirationPatch = OAuthExpirationPatch.Unchanged)
  object OAuthClientUpdateRequest {
    implicit val decoder: Decoder[OAuthClientUpdateRequest] = Decoder.instance { cursor =>
      for {
        expectedVersion <- cursor.get[Long]("expectedVersion")
        name            <- cursor.get[Option[String]]("name")
        scopes          <- cursor.get[Option[Set[OAuthScope]]]("scopes")
        projectAccess   <- cursor.get[Option[OAuthProjectAccess]]("projectAccess")
        expiresAt       <- decodeExpirationPatch(cursor)
      } yield OAuthClientUpdateRequest(expectedVersion, name, scopes, projectAccess, expiresAt)
    }

    implicit val encoder: Encoder.AsObject[OAuthClientUpdateRequest] = Encoder.AsObject.instance { request =>
      val base = JsonObject(
        "expectedVersion" -> Json.fromLong(request.expectedVersion),
        "name"            -> request.name.fold(Json.Null)(Json.fromString),
        "scopes"          -> request.scopes.fold(Json.Null)(scopesJson),
        "projectAccess"   -> request.projectAccess.fold(Json.Null)(Encoder[OAuthProjectAccess].apply)
      )
      request.expiresAt match {
        case OAuthExpirationPatch.Unchanged  => base
        case OAuthExpirationPatch.Clear      => base.add("expiresAt", Json.Null)
        case OAuthExpirationPatch.Set(value) => base.add("expiresAt", expirationJson(Some(value)))
      }
    }
  }

  private[oauth] object OAuthExpirationDecodingFailure {
    private val Marker = "oauth-client-invalid-expiration"

    def at(cursor: ACursor): DecodingFailure       = DecodingFailure(Marker, cursor.history)
    def unapply(failure: DecodingFailure): Boolean = failure.message == Marker
  }

  private def decodeCreateExpiration(cursor: HCursor): Decoder.Result[Option[OAuthClientExpiresAt]] =
    expirationMember(cursor)

  private def decodeExpirationPatch(cursor: HCursor): Decoder.Result[OAuthExpirationPatch] =
    cursor.value.asObject match {
      case None => Left(DecodingFailure("OAuth client request must be an object", cursor.history))
      case Some(fields) =>
        fields("expiresAt") match {
          case None                        => Right(OAuthExpirationPatch.Unchanged)
          case Some(value) if value.isNull => Right(OAuthExpirationPatch.Clear)
          case Some(_) =>
            parseExpiration(cursor.downField("expiresAt")).map(OAuthExpirationPatch.Set.apply)
        }
    }

  private def expirationMember(cursor: HCursor): Decoder.Result[Option[OAuthClientExpiresAt]] =
    cursor.value.asObject match {
      case None => Left(DecodingFailure("OAuth client request must be an object", cursor.history))
      case Some(fields) =>
        fields("expiresAt") match {
          case None                        => Right(None)
          case Some(value) if value.isNull => Right(None)
          case Some(_)                     => parseExpiration(cursor.downField("expiresAt")).map(value => Some(value): Option[OAuthClientExpiresAt])
        }
    }

  private def parseExpiration(cursor: ACursor): Decoder.Result[OAuthClientExpiresAt] =
    cursor.focus
      .flatMap(_.asString)
      .flatMap(value => Try(Instant.parse(value)).toOption)
      .map(OAuthClientExpiresAt.apply)
      .toRight(OAuthExpirationDecodingFailure.at(cursor))

  private def canonicalExpiration(value: OAuthClientExpiresAt): Option[OAuthClientExpiresAt] = {
    val instant   = value.asInstanceOf[Instant]
    val canonical = Instant.ofEpochSecond(instant.getEpochSecond, instant.getNano.toLong / 1000L * 1000L)
    Option
      .when(
        !canonical.isBefore(MinimumExpiration) &&
          canonical.isBefore(MaximumExpirationExclusive) &&
          ClosedAuditTimestamp.matcher(canonical.toString).matches()
      )(OAuthClientExpiresAt(canonical))
  }

  private def validatedExpiration(requested: Option[OAuthClientExpiresAt], now: Instant)
    : Either[OAuthClientManagementResult[Nothing], Option[OAuthClientExpiresAt]] = {
    val canonical = requested.flatMap(canonicalExpiration)
    if (requested.isDefined && canonical.isEmpty) Left(InvalidExpiration)
    else if (canonical.exists(expiration => !expiration.asInstanceOf[Instant].isAfter(now))) Left(InvalidExpiration)
    else Right(canonical)
  }

  private def expirationJson(value: Option[OAuthClientExpiresAt]): Json =
    value.fold(Json.Null)(expiration => Json.fromString(expiration.toString))

  final case class OAuthClientVersionRequest(expectedVersion: Long)
  final case class OAuthUserSummary(userId: UserId, displayName: String)
  final case class OAuthClientView(
    clientId: OAuthClientPublicId,
    name: String,
    status: OAuthClientEffectiveStatus,
    scopes: Set[OAuthScope],
    projectAccess: OAuthProjectAccess,
    version: Long,
    policyVersion: Long,
    secretVersion: Long,
    serviceAccount: OAuthUserSummary,
    createdAt: OAuthClientCreatedAt,
    createdBy: OAuthUserSummary,
    modifiedAt: OAuthClientModifiedAt,
    modifiedBy: OAuthUserSummary,
    secretRotatedAt: OAuthClientSecretRotatedAt,
    secretRotatedBy: OAuthUserSummary,
    expiresAt: Option[OAuthClientExpiresAt],
    revokedAt: Option[OAuthClientRevokedAt],
    revokedBy: Option[OAuthUserSummary],
    lastUsedAt: Option[OAuthClientLastUsedAt])
  final case class OAuthClientSecretView(client: OAuthClientView, clientSecret: String) {
    override def toString: String = s"OAuthClientSecretView(client=$client,clientSecret=<redacted>)"
  }
  final case class OAuthAuditEventView(
    id: OAuthAuditEventId,
    occurredAt: OAuthAuditOccurredAt,
    actorType: OAuthAuditActorType,
    actorUserId: Option[UserId],
    actor: Option[OAuthUserSummary],
    eventType: OAuthAuditEventType,
    outcome: OAuthAuditOutcome,
    metadata: Json)

  sealed trait OAuthClientManagementResult[+A]
  object OAuthClientManagementResult {
    final case class Success[A](value: A)               extends OAuthClientManagementResult[A]
    case object OrganizationNotFound                    extends OAuthClientManagementResult[Nothing]
    case object ClientNotFound                          extends OAuthClientManagementResult[Nothing]
    case object ProjectNotFound                         extends OAuthClientManagementResult[Nothing]
    case object OperationNotPermitted                   extends OAuthClientManagementResult[Nothing]
    case object InvalidPolicy                           extends OAuthClientManagementResult[Nothing]
    case object InvalidExpiration                       extends OAuthClientManagementResult[Nothing]
    case object NameConflict                            extends OAuthClientManagementResult[Nothing]
    final case class StaleVersion(currentVersion: Long) extends OAuthClientManagementResult[Nothing]
    case object NotActive                               extends OAuthClientManagementResult[Nothing]
    case object PersistenceUnavailable                  extends OAuthClientManagementResult[Nothing]
  }

  private def normalizedName(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

  private def fromGrantabilityError(error: OAuthGrantabilityError): OAuthClientManagementResult[Nothing] =
    error match {
      case OAuthGrantabilityError.OrganizationNotFound  => OrganizationNotFound
      case OAuthGrantabilityError.OperationNotPermitted => OperationNotPermitted
      case OAuthGrantabilityError.InvalidPolicy         => InvalidPolicy
      case OAuthGrantabilityError.ProjectNotFound       => ProjectNotFound
    }

  private def newServiceAccount(
    id: UserId,
    name: String,
    now: Instant
  ): User =
    User(
      id,
      UserFirstName("Integration"),
      UserLastName(name),
      avatar = None,
      color = None,
      isSuperAdmin = UserIsSuperAdmin(false),
      createdAt = UserCreatedAt(now),
      modifiedAt = UserModifiedAt(now),
      archivedAt = None,
      accountType = UserAccountType.ServiceAccount
    )

  private def newClient(
    id: OAuthClientId,
    publicId: OAuthClientPublicId,
    grantability: OAuthGrantability,
    serviceAccountUserId: UserId,
    name: String,
    secretHash: OAuthClientSecretHash,
    scopes: Set[OAuthScope],
    projectAccess: OAuthProjectAccess,
    actorId: UserId,
    now: Instant,
    expiresAt: Option[OAuthClientExpiresAt]
  ): OAuthClientSnapshot = {
    val mode = projectAccess match {
      case OAuthProjectAccess.AllProjects         => OAuthProjectAccessMode.AllProjects
      case _: OAuthProjectAccess.SelectedProjects => OAuthProjectAccessMode.SelectedProjects
    }
    OAuthClientSnapshot(
      OAuthClient(
        id,
        publicId,
        grantability.organization.id,
        serviceAccountUserId,
        OAuthClientName(name),
        OAuthClientStatus.Active,
        mode,
        secretHash,
        OAuthClientSecretVersion(1L),
        OAuthClientVersion(1L),
        OAuthClientPolicyVersion(1L),
        OAuthClientCreatedAt(now),
        actorId,
        OAuthClientModifiedAt(now),
        actorId,
        OAuthClientSecretRotatedAt(now),
        actorId,
        None,
        None,
        expiresAt
      ),
      scopes,
      projectAccess
    )
  }

  private def toView(
    snapshot: OAuthClientSnapshot,
    now: Instant,
    humansById: Map[UserId, OAuthUserSummary]
  ): OAuthClientView =
    OAuthClientView(
      snapshot.client.clientId,
      snapshot.client.name.toString,
      OAuthClientEffectiveStatus.resolve(snapshot.client.status, snapshot.client.expiresAt, now),
      snapshot.scopes,
      snapshot.projectAccess,
      snapshot.client.version.asInstanceOf[Long],
      snapshot.client.policyVersion.asInstanceOf[Long],
      snapshot.client.secretVersion.asInstanceOf[Long],
      serviceAccountSummary(snapshot),
      snapshot.client.createdAt,
      humanSummary(snapshot.client.createdBy, humansById),
      snapshot.client.modifiedAt,
      humanSummary(snapshot.client.modifiedBy, humansById),
      snapshot.client.secretRotatedAt,
      humanSummary(snapshot.client.secretRotatedBy, humansById),
      snapshot.client.expiresAt,
      snapshot.client.revokedAt,
      snapshot.client.revokedBy.map(humanSummary(_, humansById)),
      snapshot.client.lastUsedAt
    )

  private def toAuditView(
    row: OAuthAuditEventRow,
    client: OAuthClientSnapshot,
    humansById: Map[UserId, OAuthUserSummary]
  ): OAuthAuditEventView = {
    val actor = row.actorType match {
      case OAuthAuditActorType.Human => row.actorUserId.map(humanSummary(_, humansById))
      case OAuthAuditActorType.ServiceAccount if row.actorUserId.contains(client.client.serviceAccountUserId) =>
        Some(serviceAccountSummary(client))
      case OAuthAuditActorType.ServiceAccount | OAuthAuditActorType.Anonymous | OAuthAuditActorType.System => None
    }
    OAuthAuditEventView(row.id, row.occurredAt, row.actorType, row.actorUserId, actor, row.eventType, row.outcome, row.metadata)
  }

  private def assembleClientView(
    snapshot: OAuthClientSnapshot,
    now: Instant,
    userRepository: UserRepository
  )(implicit
    ec: ExecutionContext
  ): DBIO[OAuthClientView] =
    assembleClientViews(Seq(snapshot), now, userRepository).map(_.head)

  private def assembleClientViews(
    snapshots: Seq[OAuthClientSnapshot],
    now: Instant,
    userRepository: UserRepository
  )(implicit
    ec: ExecutionContext
  ): DBIO[Seq[OAuthClientView]] =
    loadHumanSummaries(clientActorIds(snapshots), userRepository).map(humansById => snapshots.map(toView(_, now, humansById)))

  private def assembleAuditViews(
    events: Seq[OAuthAuditEventRow],
    client: OAuthClientSnapshot,
    userRepository: UserRepository
  )(implicit
    ec: ExecutionContext
  ): DBIO[Seq[OAuthAuditEventView]] =
    loadHumanSummaries(events.collect { case row if row.actorType == OAuthAuditActorType.Human => row.actorUserId }.flatten, userRepository)
      .map(humansById => events.map(toAuditView(_, client, humansById)))

  private def clientActorIds(snapshots: Seq[OAuthClientSnapshot]): Seq[UserId] =
    snapshots.flatMap(snapshot =>
      Seq(snapshot.client.createdBy, snapshot.client.modifiedBy, snapshot.client.secretRotatedBy) ++ snapshot.client.revokedBy
    )

  private def loadHumanSummaries(
    ids: Seq[UserId],
    userRepository: UserRepository
  )(implicit
    ec: ExecutionContext
  ): DBIO[Map[UserId, OAuthUserSummary]] = {
    val uniqueIds = ids.distinct.sortBy(_.toString)
    if (uniqueIds.isEmpty) DBIO.successful(Map.empty)
    else
      userRepository
        .list(UserFilters(ids = Some(uniqueIds), includeArchived = true))
        .map(_.iterator.map(user => user.id -> OAuthUserSummary(user.id, user.fullName.toString)).toMap)
  }

  private def serviceAccountSummary(snapshot: OAuthClientSnapshot): OAuthUserSummary =
    OAuthUserSummary(snapshot.client.serviceAccountUserId, s"Integration ${snapshot.client.name}")

  private def humanSummary(userId: UserId, humansById: Map[UserId, OAuthUserSummary]): OAuthUserSummary =
    humansById.getOrElse(userId, missingHumanSummary(userId))

  private def missingHumanSummary(userId: UserId): OAuthUserSummary =
    OAuthUserSummary(userId, s"Unknown user ($userId)")

  private def managementAudit(
    id: OAuthAuditEventId,
    now: Instant,
    snapshot: OAuthClientSnapshot,
    actorId: UserId,
    eventType: OAuthAuditEventType,
    correlationId: Option[OAuthAuditCorrelationId],
    metadata: Json
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      id,
      OAuthAuditOccurredAt(now),
      OAuthAuditCategory.Management,
      Some(snapshot.client.organizationId),
      Some(snapshot.client.id),
      OAuthAuditActorType.Human,
      Some(actorId),
      eventType,
      OAuthAuditOutcome.Success,
      correlationId,
      tokenJti = None,
      sourceIp = None,
      metadata
    )

  private def createMetadata(snapshot: OAuthClientSnapshot): Json =
    Json.obj(
      "scopes"        -> scopesJson(snapshot.scopes),
      "projectMode"   -> Json.fromString(snapshot.client.projectAccessMode.entryName),
      "projectIds"    -> projectIdsJson(snapshot.projectAccess),
      "version"       -> Json.fromLong(snapshot.client.version.asInstanceOf[Long]),
      "policyVersion" -> Json.fromLong(snapshot.client.policyVersion.asInstanceOf[Long]),
      "secretVersion" -> Json.fromLong(snapshot.client.secretVersion.asInstanceOf[Long]),
      "expiresAt"     -> expirationJson(snapshot.client.expiresAt)
    )

  private def updateMetadata(before: OAuthClientSnapshot, after: OAuthClientSnapshot): Json =
    Json.obj(
      "beforeScopes"        -> scopesJson(before.scopes),
      "afterScopes"         -> scopesJson(after.scopes),
      "beforeProjectMode"   -> Json.fromString(before.client.projectAccessMode.entryName),
      "afterProjectMode"    -> Json.fromString(after.client.projectAccessMode.entryName),
      "beforeProjectIds"    -> projectIdsJson(before.projectAccess),
      "afterProjectIds"     -> projectIdsJson(after.projectAccess),
      "beforeVersion"       -> Json.fromLong(before.client.version.asInstanceOf[Long]),
      "afterVersion"        -> Json.fromLong(after.client.version.asInstanceOf[Long]),
      "beforePolicyVersion" -> Json.fromLong(before.client.policyVersion.asInstanceOf[Long]),
      "afterPolicyVersion"  -> Json.fromLong(after.client.policyVersion.asInstanceOf[Long]),
      "beforeExpiresAt"     -> expirationJson(before.client.expiresAt),
      "afterExpiresAt"      -> expirationJson(after.client.expiresAt)
    )

  private def rotateMetadata(after: OAuthClientSnapshot): Json =
    Json.obj(
      "beforeVersion"       -> Json.fromLong(after.client.version.asInstanceOf[Long] - 1L),
      "afterVersion"        -> Json.fromLong(after.client.version.asInstanceOf[Long]),
      "beforeSecretVersion" -> Json.fromLong(after.client.secretVersion.asInstanceOf[Long] - 1L),
      "afterSecretVersion"  -> Json.fromLong(after.client.secretVersion.asInstanceOf[Long])
    )

  private def revokeMetadata(after: OAuthClientSnapshot): Json =
    Json.obj(
      "beforeVersion"       -> Json.fromLong(after.client.version.asInstanceOf[Long] - 1L),
      "afterVersion"        -> Json.fromLong(after.client.version.asInstanceOf[Long]),
      "beforePolicyVersion" -> Json.fromLong(after.client.policyVersion.asInstanceOf[Long] - 1L),
      "afterPolicyVersion"  -> Json.fromLong(after.client.policyVersion.asInstanceOf[Long])
    )

  private def scopesJson(scopes: Set[OAuthScope]): Json =
    Json.arr(OAuthScope.values.filter(scopes).map(scope => Json.fromString(scope.value)): _*)

  private def projectIdsJson(access: OAuthProjectAccess): Json = {
    val projectIds = access match {
      case OAuthProjectAccess.AllProjects                  => Seq.empty
      case OAuthProjectAccess.SelectedProjects(projectIds) => projectIds.toSeq.sortBy(_.toString)
    }
    Json.arr(projectIds.map(projectId => Json.fromString(projectId.toString)): _*)
  }
}
