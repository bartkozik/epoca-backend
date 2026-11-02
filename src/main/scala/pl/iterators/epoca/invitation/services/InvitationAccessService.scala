package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.{InvitationAccessRepository, InvitationOnboardingRepository}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.EmailIdentityRepository
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{EmailCanonicalization, PostgresIOTransactor}

import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContext

final class InvitationAccessService(
  transactor: PostgresIOTransactor,
  invitations: InvitationOnboardingRepository,
  accesses: InvitationAccessRepository,
  emails: EmailIdentityRepository,
  secrets: InvitationSecretService,
  browser: InvitationBrowserSecurity,
  limits: InvitationRateLimitService,
  clock: Clock) {
  import InvitationAccessService._
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def hash(value: String): Digest   = Digest(secrets.hash(value))
  def exchange(
    token: String,
    existingCookie: Option[String],
    origin: Option[String],
    contentType: Option[String],
    trustedIp: String
  ): IO[Result] =
    if (!browser.exchangeAllowed(origin, contentType)) IO.pure(Forbidden)
    else
      limits.exchange(trustedIp).flatMap {
        case InvitationRateLimitService.Allowed       => transactor.executeTransactionally(exchangeAction(token, existingCookie))
        case InvitationRateLimitService.Denied(retry) => IO.pure(RateLimited(retry))
      }
  def organizationSlug(cookie: String): IO[Option[String]] =
    transactor.executeTransactionally(withContext(cookie, allowCompleted = false)(DBIO.successful(Option.empty[String])) {
      (
        _,
        _,
        recipient
      ) =>
        DBIO.successful(Some(recipient.organization.slug))
    })
  def summary(cookie: String): IO[Result] = transactor.executeTransactionally(summaryAction(cookie))
  def revoke(
    cookie: String,
    origin: Option[String],
    csrf: Option[String]
  ): IO[Result] = transactor.executeTransactionally(revokeAction(cookie, origin, csrf))

  private def pending(record: Record): Boolean = {
    val i = record.invitation
    i.acceptAt.isEmpty && i.rejectAt.isEmpty && i.archivedAt.isEmpty && i.expireAt.exists(t => (t: Instant).isAfter(clock.instant()))
  }

  /** Metadata lookup does not lock. Canonical address, UUID-sorted users, organization, memberships, invitation, access is the shared lock order. */
  private def withInvitation[A](
    hint: Record,
    additionalUsers: Vector[UserId] = Vector.empty,
    updateUsers: Boolean = false,
    proofOrganizations: Vector[OrganizationId] = Vector.empty,
    allowAmbiguous: Boolean = false
  )(
    invalid: => DBIO[A]
  )(
    operation: (Record, Recipient) => DBIO[A]
  ): DBIO[A] =
    hint.canonicalEmail match {
      case None => invalid
      case Some(email) =>
        for {
          _       <- emails.lock(email)
          initial <- emails.resolve(email)
          ids = (additionalUsers ++ hint.acceptedUserId ++ (initial match {
                  case Resolution.Single(user, _) => Vector(user.id)
                  case _                          => Vector.empty
                })).distinct.sortBy(_.toString)
          lockedUsers <- DBIO
                           .sequence(
                             ids.map(id =>
                               if (updateUsers) sql"""SELECT id::text FROM "user" WHERE id=${id.toString}::uuid FOR UPDATE""".as[String]
                               else sql"""SELECT id::text FROM "user" WHERE id=${id.toString}::uuid FOR SHARE""".as[String]
                             )
                           )
                           .map(_.flatten.map(id => UserId(UUID.fromString(id))).toSet)
          _ <- DBIO.sequence(
                 (proofOrganizations :+ hint.invitation.organizationId).distinct
                   .sortBy(_.toString)
                   .map(id => sql"SELECT id::text FROM organization WHERE id=${id.toString}::uuid FOR UPDATE".as[String])
               )
          org <- sql"SELECT name,slug FROM organization WHERE id=${hint.invitation.organizationId.toString}::uuid AND archived_at IS NULL FOR UPDATE"
                   .as[(String, String)]
                   .headOption
          _ <-
            DBIO.sequence(
              ids.map(id =>
                sql"SELECT id::text FROM organization_user WHERE organization_id=${hint.invitation.organizationId.toString}::uuid AND user_id=${id.toString}::uuid ORDER BY id FOR SHARE"
                  .as[String]
              )
            )
          current <- emails.resolve(email)
          result <- (org, current) match {
                      case (Some((name, slug)), resolution) if (allowAmbiguous || resolution != Resolution.Ambiguous) && (resolution match {
                            case Resolution.Single(user, _) => lockedUsers(user.id)
                            case _                          => true
                          }) =>
                        invitations.lockInvitation(hint.invitation.id).flatMap {
                          case Some(record)
                              if record.invitation.organizationId == hint.invitation.organizationId && record.canonicalEmail
                                .contains(email) && record.acceptedUserId.forall(lockedUsers) =>
                            sql"SELECT is_enabled FROM saml_config WHERE organization_id=${record.invitation.organizationId.toString}::uuid"
                              .as[Boolean]
                              .headOption
                              .flatMap { enabled =>
                                operation(record, Recipient(SafeOrganization(name, slug), resolution, enabled.contains(true), lockedUsers))
                              }
                          case _ => invalid
                        }
                      case _ => invalid
                    }
        } yield result
    }
  private[invitation] def exchangeAction(token: String, existingCookie: Option[String]): DBIO[Result] =
    invitations.findByTokenHash(hash(token)).flatMap {
      case Some(hint) =>
        withInvitation(hint)(DBIO.successful[Result](InvalidInvitation)) { (record, recipient) =>
          if (record.protocolVersion != 2 || !pending(record) || !record.tokenHash.contains(hash(token))) DBIO.successful(InvalidInvitation)
          else
            existingCookie.map(cookie => accesses.lockByCookieHash(hash(cookie))).getOrElse(DBIO.successful(None)).flatMap { previous =>
              val reusable = previous.filter(a =>
                a.invitationId == record.invitation.id && a.generation == record.generation && a.completedAt.isEmpty && a.expiresAt
                  .isAfter(clock.instant())
              )
              issue(
                record,
                recipient,
                Proof(Some(clock.instant()), reusable.flatMap(_.proof.saml)),
                reusable.flatMap(a => existingCookie.map(_ -> a))
              )
            }
        }
      case _ => DBIO.successful(InvalidInvitation)
    }
  private def issue(
    record: Record,
    recipient: Recipient,
    proof: Proof,
    previous: Option[(String, Access)]
  ): DBIO[Result] =
    previous match {
      case Some((cookie, access)) =>
        val csrf = secrets.csrfToken(cookie)
        // Preserve context identity and saml_request linkage while enriching evidence. Never extend its TTL.
        val enriched = access.copy(proof = proof, csrfHash = hash(csrf))
        accesses
          .update(enriched, record.generation)
          .map(updated => if (updated) Granted(cookie, dto(record, recipient, enriched, csrf)) else InvalidInvitation)
      case None =>
        val cookie = secrets.generate()
        val csrf   = secrets.csrfToken(cookie)
        val expiry = Vector(clock.instant().plusSeconds(900), record.invitation.expireAt.get: Instant).min
        val access = Access(
          InvitationAccessId(UUID.randomUUID()),
          hash(cookie),
          hash(csrf),
          record.invitation.id,
          record.generation,
          proof,
          None,
          None,
          expiry,
          None
        )
        accesses.insert(access).map(_ => Granted(cookie, dto(record, recipient, access, csrf)))
    }

  private def dto(
    record: Record,
    recipient: Recipient,
    access: Access,
    csrf: String
  ): SafeAccessDto =
    SafeAccessDto(
      recipient.organization,
      record.invitation.identityValue,
      record.firstName,
      record.lastName,
      record.invitation.roles.map(_.toString),
      record.revision.toString,
      access.expiresAt,
      csrf,
      if (access.completedAt.isDefined) "Accept"
      else
        recipient.resolution match {
          case Resolution.Empty => "CreateAccount"
          case _                => "SignIn"
        },
      recipient.ssoEnabled
    )

  /** Server-only integration for activation: supply verified authentication candidates before locking; call inside one transaction, then check CSRF
    * and expectedRevision before writes. All current recipient/accepted/verified/candidate user rows and memberships are already locked in canonical
    * order. Completed contexts are admitted only explicitly for the same verified user's session retry; no proofs are reopened. Only activation may
    * admit ambiguous pending recipients, and must verify Origin, CSRF and recipient proof before returning a safe conflict.
    */
  def cookieHeader(grant: Granted): String = browser.cookie(grant.cookie, java.time.Duration.between(clock.instant(), grant.dto.expiresAt).getSeconds)

  private[epoca] def samlStartAction[A](
    cookie: String,
    origin: Option[String],
    csrf: Option[String],
    users: Vector[UserId],
    proofOrgs: Vector[OrganizationId]
  )(
    operation: (Record, Access) => DBIO[Option[A]]
  ): DBIO[Option[A]] =
    withContext(cookie, allowCompleted = false, users, updateUsers = true, proofOrgs)(DBIO.successful(Option.empty[A])) {
      (
        record,
        context,
        _
      ) =>
        if (browser.mutationAllowed(origin, csrf, context.csrfHash)) operation(record, context) else DBIO.successful(None)
    }

  private[invitation] def contextHint(cookie: String): DBIO[Option[Access]] = accesses.findByCookieHash(hash(cookie))

  private[epoca] def withContext[A](
    cookie: String,
    allowCompleted: Boolean,
    authenticatedCandidates: Vector[UserId] = Vector.empty,
    updateUsers: Boolean = false,
    proofOrganizations: Vector[OrganizationId] = Vector.empty,
    allowAmbiguous: Boolean = false
  )(
    invalid: => DBIO[A]
  )(
    operation: (Record, Access, Recipient) => DBIO[A]
  ): DBIO[A] =
    accesses.findByCookieHash(hash(cookie)).flatMap {
      case Some(hint) =>
        invitations.find(hint.invitationId).flatMap {
          case Some(invitation) =>
            withInvitation(invitation, authenticatedCandidates ++ hint.verifiedUserId, updateUsers, proofOrganizations, allowAmbiguous)(invalid) {
              (record, recipient) =>
                accesses.lockByCookieHash(hash(cookie)).flatMap {
                  case Some(access)
                      if record.protocolVersion == 2 && access.verifiedUserId.forall(
                        recipient.lockedUserIds
                      ) && access.invitationId == record.invitation.id && access.generation == record.generation && access.expiresAt
                        .isAfter(clock.instant()) && (recipient.resolution != Resolution.Ambiguous || access.completedAt.isEmpty) &&
                        ((access.completedAt.isEmpty && pending(
                          record
                        )) || (allowCompleted && access.completedAt.isDefined && record.invitation.acceptAt.isDefined && record.invitation.archivedAt.isEmpty && record.invitation.rejectAt.isEmpty && access.verifiedUserId.isDefined && access.verifiedUserId == record.acceptedUserId)) =>
                    operation(record, access, recipient)
                  case _ => invalid
                }
            }
          case _ => invalid
        }
      case _ => invalid
    }
  private[invitation] def summaryAction(cookie: String): DBIO[Result] =
    withContext(cookie, allowCompleted = true)(DBIO.successful[Result](InvalidInvitation)) {
      (
        record,
        access,
        recipient
      ) =>
        val csrf = secrets.csrfToken(cookie)
        // Old random-CSRF contexts must be exchanged again; a GET never changes authorization state.
        DBIO.successful(
          if (secrets.matches(csrf, access.csrfHash.toArray)) Granted(cookie, dto(record, recipient, access, csrf)) else InvalidInvitation
        )
    }
  private[invitation] def revokeAction(
    cookie: String,
    origin: Option[String],
    csrf: Option[String]
  ): DBIO[Result] =
    withContext(cookie, allowCompleted = true)(DBIO.successful[Result](InvalidInvitation)) {
      (
        _,
        access,
        _
      ) =>
        if (!browser.mutationAllowed(origin, csrf, access.csrfHash)) DBIO.successful(Forbidden)
        else accesses.revoke(access.id, clock.instant()).map(_ => Revoked)
    }

  /** ACS owns address/users/organization/membership/invitation/context/request locks. No raw cookie is retained or returned here. */
  private[epoca] def trustedSamlContextLocked(
    record: Record,
    proof: VerifiedSamlProof,
    requestId: String,
    previous: Option[Access],
    verifiedUserId: Option[UserId]
  ): DBIO[Option[Access]] = {
    val now = clock.instant()
    val reusable = previous.forall(a =>
      a.invitationId == record.invitation.id && a.generation == record.generation &&
        a.completedAt.isEmpty && a.expiresAt.isAfter(now) &&
        (a.verifiedUserId.isEmpty || verifiedUserId.isEmpty || a.verifiedUserId == verifiedUserId)
    )
    if (
      !reusable || record.protocolVersion != 2 || !pending(record) || proof.organizationId != record.invitation.organizationId ||
      !record.canonicalEmail.contains(proof.canonicalEmail) || proof.subject.trim.isEmpty || proof.verifiedAt.isAfter(now) ||
      !proof.verifiedAt.isAfter(now.minusSeconds(900))
    ) DBIO.successful(None)
    else
      sql"SELECT 1 FROM saml_config WHERE organization_id=${proof.organizationId.toString}::uuid AND is_enabled AND trust_epoch=${proof.trustEpoch} AND idp_entity_id=${proof.issuer} FOR SHARE"
        .as[Int]
        .flatMap { current =>
          if (current.isEmpty) DBIO.successful(None)
          else {
            val context = previous match {
              case Some(value) =>
                value.copy(
                  proof = value.proof.copy(saml = Some(proof)),
                  samlRequestId = Some(requestId),
                  verifiedUserId = value.verifiedUserId.orElse(verifiedUserId)
                )
              case None =>
                Access(
                  InvitationAccessId(UUID.randomUUID()),
                  hash(secrets.generate()),
                  hash(secrets.generate()),
                  record.invitation.id,
                  record.generation,
                  Proof(None, Some(proof)),
                  verifiedUserId,
                  Some(requestId),
                  Vector(now.plusSeconds(900), record.invitation.expireAt.get: Instant).min,
                  None
                )
            }
            previous match {
              case Some(_) => accesses.update(context, record.generation).map(updated => Option.when(updated)(context))
              case None    => accesses.insert(context).map(_ => Some(context))
            }
          }
        }
  }

  /** Final nonce-authenticated callback only; caller already revalidated the linked pending context and current SAML trust. */
  private[epoca] def rotateSamlCookieLocked(
    record: Record,
    context: Access,
    recipient: Recipient,
    nextAction: String
  ): DBIO[Option[Granted]] = {
    val cookie = secrets.generate()
    val csrf   = secrets.csrfToken(cookie)
    accesses
      .rotateCookie(context, hash(cookie), hash(csrf), clock.instant())
      .map(updated => Option.when(updated)(Granted(cookie, dto(record, recipient, context, csrf).copy(nextAction = nextAction))))
  }

  /** Trusted ACS adapter only. Never bind this method's proof from JSON or infer it from a JWT/fresh flag. */
  private[epoca] def fromVerifiedSamlAction(
    id: InvitationId,
    generation: Long,
    proof: VerifiedSamlProof,
    existingCookie: Option[String]
  ): DBIO[Result] =
    invitations.find(id).flatMap {
      case Some(hint) =>
        withInvitation(hint)(DBIO.successful[Result](InvalidInvitation)) { (record, recipient) =>
          val now = clock.instant()
          if (
            record.protocolVersion != 2 || !pending(
              record
            ) || record.generation != generation || proof.organizationId != record.invitation.organizationId ||
            !record.canonicalEmail.contains(EmailCanonicalization.canonical(proof.canonicalEmail)) || proof.subject.trim.isEmpty ||
            proof.verifiedAt.isAfter(now) || !proof.verifiedAt.isAfter(now.minusSeconds(900))
          ) DBIO.successful(InvalidInvitation)
          else
            sql"SELECT trust_epoch,idp_entity_id FROM saml_config WHERE organization_id=${proof.organizationId.toString}::uuid AND is_enabled=true FOR SHARE"
              .as[(Long, String)]
              .headOption
              .flatMap {
                case Some((epoch, issuer)) if epoch == proof.trustEpoch && issuer == proof.issuer =>
                  existingCookie.map(c => accesses.lockByCookieHash(hash(c))).getOrElse(DBIO.successful(None)).flatMap { previous =>
                    val reusable =
                      previous.filter(a => a.invitationId == id && a.generation == generation && a.completedAt.isEmpty && a.expiresAt.isAfter(now))
                    issue(
                      record,
                      recipient,
                      Proof(
                        reusable.flatMap(_.proof.emailVerifiedAt),
                        Some(proof.copy(canonicalEmail = EmailCanonicalization.canonical(proof.canonicalEmail)))
                      ),
                      reusable.flatMap(a => existingCookie.map(_ -> a))
                    )
                  }
                case _ => DBIO.successful(InvalidInvitation)
              }
        }
      case _ => DBIO.successful(InvalidInvitation)
    }
}
object InvitationAccessService {
  final case class SafeOrganization(name: String, slug: String)
  final case class SafeAccessDto(
    organization: SafeOrganization,
    email: String,
    firstName: Option[String],
    lastName: Option[String],
    roles: Seq[String],
    revision: String,
    expiresAt: Instant,
    csrfToken: String,
    nextAction: String,
    ssoEnabled: Boolean) {
    override def toString: String = "SafeAccessDto(<redacted>)"
  }
  private[epoca] final case class Recipient(
    organization: SafeOrganization,
    resolution: Resolution,
    ssoEnabled: Boolean,
    lockedUserIds: Set[UserId]) {
    override def toString: String = "InvitationRecipient(<redacted>)"
  }
  sealed trait Result
  final case class Granted(cookie: String, dto: SafeAccessDto) extends Result {
    override def toString: String = "InvitationAccess.Granted(<redacted>)"
  }
  case object InvalidInvitation                         extends Result
  case object Forbidden                                 extends Result
  case object Revoked                                   extends Result
  final case class RateLimited(retryAfterSeconds: Long) extends Result
}
