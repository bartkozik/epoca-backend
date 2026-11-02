package pl.iterators.epoca.invitation.services

import io.circe.Json
import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.{AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.{Access, Audit, Record, VerifiedSamlProof}
import pl.iterators.epoca.invitation.repositories.{InvitationAccessRepository, InvitationOnboardingRepository}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserId}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.saml.domain.SamlDomain.SamlRequestId
import pl.iterators.epoca.saml.domain.{SamlBindingKey, SamlIdentityBinding, SamlRequest}
import pl.iterators.epoca.saml.repositories.{SamlIdentityBindingRepository, SamlRequestRepository}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserCreationActions
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.EmailCanonicalization

import java.time.Clock
import java.util.UUID
import scala.concurrent.ExecutionContext

/** All methods are DBIO only. Caller owns address → sorted users UPDATE → sorted organizations → memberships → invitation → context locks. */
final class InvitationActivationActions(
  invitations: InvitationOnboardingRepository,
  accesses: InvitationAccessRepository,
  users: UserRepository,
  identities: UserIdentityRepository,
  memberships: OrganizationUserRepository,
  creation: UserCreationActions,
  authentication: AccountAuthenticationValidator,
  clock: Clock,
  samlRequests: SamlRequestRepository,
  samlBindings: SamlIdentityBindingRepository) {
  import InvitationActivationService._
  private implicit val ec: ExecutionContext                 = ExecutionContext.global
  def authenticationValid(auth: AuthContext): DBIO[Boolean] = authentication.validate(auth, clock.instant())

  private def recipientScopeAllowed(record: Record, auth: AuthContext): Boolean =
    auth.accountAuthentication.map(_.evidence) match {
      case Some(bound: BoundSaml) => bound.organizationId == record.invitation.organizationId
      case _                      => true
    }

  def authenticationValid(record: Record, auth: AuthContext): DBIO[Boolean] =
    if (recipientScopeAllowed(record, auth)) authenticationValid(auth) else DBIO.successful(false)

  def recipientMatches(record: Record, auth: AuthContext): DBIO[Boolean] =
    if (!recipientScopeAllowed(record, auth)) DBIO.successful(false)
    else
      identities.list(UserIdentityFilters(userId = Some(auth.id), forUpdate = true)).flatMap { owned =>
        val matchingContact = owned.exists(i =>
          i.confirmed && (record.invitation.identityType match {
            case IdentityType.MailAddress =>
              i.`type` == IdentityType.MailAddress && EmailCanonicalization.canonical(i.value) == EmailCanonicalization
                .canonical(record.invitation.identityValue)
            case IdentityType.Phone => i.`type` == IdentityType.Phone && i.value == record.invitation.identityValue
            case _                  => false
          })
        )
        if (matchingContact) DBIO.successful(true)
        else
          auth.accountAuthentication.map(_.evidence) match {
            case Some(b: BoundSaml)
                if b.organizationId == record.invitation.organizationId && record.invitation.identityType == IdentityType.MailAddress =>
              authenticationValid(auth).flatMap {
                case false => DBIO.successful(false)
                case true =>
                  sql"SELECT 1 FROM saml_identity_binding WHERE organization_id=${b.organizationId.toString}::uuid AND trust_epoch=${b.trustEpoch} AND issuer=${b.issuer} AND subject=${b.subject} AND user_id=${auth.id.toString}::uuid AND canonical_email=${EmailCanonicalization
                      .canonical(record.invitation.identityValue)} FOR SHARE".as[Int].map(_.nonEmpty)
              }
            case _ => DBIO.successful(false)
          }
      }
  def authenticateRecipient(record: Record, auth: AuthContext): DBIO[Unit] = for {
    valid   <- authenticationValid(record, auth)
    _       <- if (valid) DBIO.successful(()) else fail(SignInRequired)
    matches <- recipientMatches(record, auth)
    _       <- if (matches) DBIO.successful(()) else fail(IdentityMismatch)
  } yield ()

  def contextProof(record: Record, context: Access): DBIO[Boolean] =
    if (context.proof.emailVerifiedAt.exists(at => !at.isAfter(clock.instant()))) DBIO.successful(true)
    else
      context.proof.saml match {
        case Some(p)
            if p.organizationId == record.invitation.organizationId && record.canonicalEmail
              .contains(p.canonicalEmail) && !p.verifiedAt.isAfter(clock.instant()) && p.verifiedAt.isAfter(clock.instant().minusSeconds(900)) =>
          sql"SELECT 1 FROM saml_config WHERE organization_id=${p.organizationId.toString}::uuid AND is_enabled AND trust_epoch=${p.trustEpoch} AND idp_entity_id=${p.issuer} FOR SHARE"
            .as[Int]
            .map(_.nonEmpty)
        case _ => DBIO.successful(false)
      }

  def requestHint(context: Access): DBIO[Option[SamlRequest]] =
    context.samlRequestId.fold[DBIO[Option[SamlRequest]]](DBIO.successful(None))(id => samlRequests.find(SamlRequestId(id)))

  private def linkedRequest(context: Access): DBIO[Option[SamlRequest]] =
    context.samlRequestId
      .fold[DBIO[Option[SamlRequest]]](DBIO.successful(None))(id => samlRequests.lock(SamlRequestId(id)))
      .map(_.filter { request =>
        request.used && request.token.isDefined && request.invitationAccessId.contains(context.id) && request.purpose.contains("Invitation") &&
        context.proof.saml.exists(p =>
          request.organizationId.contains(p.organizationId) && request.trustEpoch.contains(p.trustEpoch) &&
            request.verifiedIssuer.contains(p.issuer) && request.verifiedSubject.contains(p.subject) &&
            request.verifiedCanonicalEmail.contains(p.canonicalEmail) && request.verifiedAt.contains(p.verifiedAt)
        )
      })

  def currentSamlProof(record: Record, context: Access): DBIO[Option[VerifiedSamlProof]] = context.proof.saml match {
    case Some(p)
        if p.organizationId == record.invitation.organizationId && record.canonicalEmail.contains(p.canonicalEmail) &&
          p.subject.trim.nonEmpty && !p.verifiedAt.isAfter(clock.instant()) && p.verifiedAt.isAfter(clock.instant().minusSeconds(300)) =>
      for {
        current <-
          sql"SELECT 1 FROM saml_config WHERE organization_id=${p.organizationId.toString}::uuid AND is_enabled AND trust_epoch=${p.trustEpoch} AND idp_entity_id=${p.issuer} FOR SHARE"
            .as[Int]
        request <- linkedRequest(context)
      } yield Option.when(current.nonEmpty && request.isDefined)(p)
    case _ => DBIO.successful(None)
  }

  def serverAuthentication(
    context: Access,
    lockedUsers: Set[UserId],
    lockedOrganizations: Set[OrganizationId]
  ): DBIO[Option[AuthContext]] =
    linkedRequest(context).flatMap {
      case Some(request) =>
        (request.userId, request.accountAuthentication) match {
          case (Some(userId), Some(proof)) if context.verifiedUserId.contains(userId) && lockedUsers(userId) && (proof.evidence match {
                case b: BoundSaml => lockedOrganizations(b.organizationId) && request.organizationId.contains(b.organizationId)
                case _            => true
              }) =>
            val method = proof.evidence match {
              case Credential(_, identityId) =>
                identities.find(UserIdentityFilters(id = Some(identityId))).map(_.map(i => AuthContextAuthedBy(i.`type`, Some(i.id))).toSet)
              case _: BoundSaml => DBIO.successful(Set(AuthContextAuthedBy(IdentityType.Saml, None)))
            }
            method.flatMap { methods =>
              val auth =
                AuthContext(userId, UserIsSuperAdmin(false), Seq.empty, methods, Seq.empty, proof.authenticatedAt, fresh = false, Some(proof))
              authenticationValid(auth).map(valid => Option.when(valid)(auth))
            }
          case _ => DBIO.successful(None)
        }
      case _ => DBIO.successful(None)
    }

  def samlBinding(proof: VerifiedSamlProof): DBIO[Option[SamlIdentityBinding]] =
    samlBindings.find(SamlBindingKey(proof.organizationId, proof.trustEpoch, proof.issuer, proof.subject))

  def bindSaml(
    record: Record,
    proof: VerifiedSamlProof,
    userId: UserId
  ): DBIO[Unit] = samlBinding(proof).flatMap {
    case Some(existing) if existing.userId == userId && existing.canonicalEmail == proof.canonicalEmail => DBIO.successful(())
    case Some(_)                                                                                        => fail(IdentityConflict)
    case None =>
      samlBindings.insert(
        SamlIdentityBinding(
          SamlBindingKey(proof.organizationId, proof.trustEpoch, proof.issuer, proof.subject),
          userId,
          proof.canonicalEmail,
          proof.verifiedAt,
          Json.obj("source" -> Json.fromString("invitation-activation"), "invitationId" -> Json.fromString(record.invitation.id.toString))
        )
      )
  }

  def createSamlAccount(record: Record): DBIO[UserIdentity] = createIdentity(record, IdentityType.Saml, None)

  def createAccount(record: Record, hash: PasswordHash): DBIO[UserIdentity] = createIdentity(record, IdentityType.MailAddress, Some(hash))

  private def createIdentity(
    record: Record,
    kind: IdentityType,
    hash: Option[PasswordHash]
  ): DBIO[UserIdentity] = {
    val now = clock.instant()
    val user = User(
      UserId(UUID.randomUUID()),
      UserFirstName(record.firstName.get),
      UserLastName(record.lastName.get),
      None,
      None,
      UserIsSuperAdmin(false),
      UserCreatedAt(now),
      UserModifiedAt(now),
      None,
      UserAccountType.Human
    )
    val identity = UserIdentity(
      UserIdentityId(UUID.randomUUID()),
      user.id,
      kind,
      UserIdentityValue(record.canonicalEmail.get),
      UserIdentityMain(true),
      UserIdentityConfirmed(true),
      None,
      None,
      UserIdentityCreatedAt(now)
    )
    creation.create(user, identity, hash).flatMap {
      case Some((_, inserted)) => DBIO.successful(inserted)
      case None                => fail(IdentityConflict)
    }
  }

  def currentMember(record: Record, userId: UserId): DBIO[Boolean] = for {
    user   <- users.find(UserFilters(id = Some(userId)))
    member <- memberships.find(OrganizationUserFilters(organizationId = Some(record.invitation.organizationId), userId = Some(userId)))
  } yield user.exists(_.accountType == UserAccountType.Human) && member.isDefined

  def sessionState(record: Record, userId: UserId): DBIO[Option[(UserWithIdentities, Seq[OrganizationUser])]] = for {
    current <- currentMember(record, userId)
    state <- if (!current) DBIO.successful(None)
             else
               for {
                 user  <- users.find(UserFilters(id = Some(userId)))
                 owned <- identities.list(UserIdentityFilters(userId = Some(userId)))
                 all   <- memberships.list(OrganizationUserFilters(userId = Some(userId)))
               } yield user.map(u => (UserWithIdentities(u, owned), all))
  } yield state

  def join(record: Record, userId: UserId): DBIO[Unit] = for {
    previous <- memberships.find(
                  OrganizationUserFilters(
                    organizationId = Some(record.invitation.organizationId),
                    userId = Some(userId),
                    includeArchived = true,
                    forUpdate = true
                  )
                )
    _ <- previous match {
           case Some(member) if member.archivedAt.isEmpty => fail(AlreadyMember)
           case Some(member) => memberships.upsert(member.copy(roles = record.invitation.roles, archivedAt = None)).map(_ => ())
           case None =>
             memberships
               .insertPrepared(
                 OrganizationUser(OrganizationUserId(UUID.randomUUID()), record.invitation.organizationId, userId, record.invitation.roles, None)
               )
               .map(_ => ())
         }
    now = clock.instant()
    updated <- invitations.update(
                 record.copy(
                   invitation =
                     record.invitation.copy(acceptAt = Some(InvitationAcceptAt(now)), modifiedAt = InvitationModifiedAt(now), modifiedBy = userId),
                   acceptedUserId = Some(userId)
                 ),
                 record.revision,
                 record.generation
               )
    _ <- if (updated) DBIO.successful(()) else fail(RevisionConflict)
    _ <- invitations.appendAudit(
           Audit(
             InvitationAuditId(UUID.randomUUID()),
             record.invitation.id,
             Some(userId),
             "Accepted",
             now,
             None,
             Some(Json.obj("acceptedUserId" -> Json.fromString(userId.toString), "invitationId" -> Json.fromString(record.invitation.id.toString)))
           )
         )
  } yield ()

  def complete(context: Access, userId: UserId): DBIO[Unit] =
    accesses.update(context.copy(verifiedUserId = Some(userId), completedAt = Some(clock.instant())), context.generation).flatMap {
      case true  => DBIO.successful(())
      case false => fail(InvalidInvitation)
    }
}
