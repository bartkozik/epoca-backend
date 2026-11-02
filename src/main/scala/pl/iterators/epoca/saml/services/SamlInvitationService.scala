package pl.iterators.epoca.saml.services

import io.circe.Json
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext}
import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.{Access, Record, VerifiedSamlProof}
import pl.iterators.epoca.invitation.repositories.{InvitationAccessRepository, InvitationOnboardingRepository}
import pl.iterators.epoca.invitation.services.{InvitationAccessService, InvitationSecretService}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlBindingKey, SamlConfig, SamlIdentityBinding, SamlRequest}
import pl.iterators.epoca.saml.repositories.{SamlConfigRepository, SamlIdentityBindingRepository, SamlRequestRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.EmailCanonicalization

import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContext

/** Server-only ACS transaction. The context must come from an actually validated signed response. Caller executes the whole action transactionally;
  * no sessions or external effects occur here.
  */
class SamlInvitationService(
  requests: SamlRequestRepository,
  configs: SamlConfigRepository,
  bindings: SamlIdentityBindingRepository,
  invitations: InvitationOnboardingRepository,
  accesses: InvitationAccessRepository,
  emails: EmailIdentityRepository,
  organizations: OrganizationRepository,
  users: UserRepository,
  identities: UserIdentityRepository,
  memberships: OrganizationUserRepository,
  authentication: AccountAuthenticationValidator,
  secrets: InvitationSecretService,
  clock: Clock,
  invitationAccess: Option[InvitationAccessService]
)(implicit ec: ExecutionContext) {
  import SamlInvitationService._

  /** Both public POST adapters delegate here; the context service checks Origin/CSRF before changing its request link. */
  private[epoca] def startAction(
    request: SamlRequest,
    parsedConfig: SamlConfig,
    auth: Option[AuthContext],
    cookie: Option[String],
    origin: Option[String],
    csrf: Option[String]
  ): DBIO[Option[SamlRequest]] = {
    val organizationId = parsedConfig.organizationId
    val extraOrgs = auth.toVector.flatMap(_.accountAuthentication.toVector.flatMap(_.evidence match {
      case b: BoundSaml => Vector(b.organizationId)
      case _            => Vector.empty
    }))
    def save(context: Option[Access]): DBIO[Option[SamlRequest]] = for {
      organization <- organizations.find(OrganizationFilters(id = Some(organizationId)))
      config       <- configs.find(organizationId)
      epoch        <- configs.trustEpoch(organizationId)
      result <- (organization, config, epoch) match {
                  case (Some(_), Some(current), Some(currentEpoch))
                      if current.isEnabled && current.idpCert == parsedConfig.idpCert &&
                        current.idpEntityId == parsedConfig.idpEntityId && current.idpSsoUrl == parsedConfig.idpSsoUrl &&
                        request.organizationId.contains(organizationId) && request.browserNonceHash.exists(_.size == 32) =>
                    auth
                      .fold[DBIO[Option[AuthContext]]](DBIO.successful(None))(a =>
                        authentication.validate(a, clock.instant()).map(valid => Option.when(valid)(a))
                      )
                      .flatMap { trusted =>
                        if (context.flatMap(_.verifiedUserId).exists(id => trusted.exists(_.id != id))) DBIO.successful(None)
                        else {
                          val persisted = request.copy(
                            trustEpoch = Some(currentEpoch),
                            userId = trusted.map(_.id),
                            accountAuthentication = trusted.flatMap(_.accountAuthentication),
                            purpose = Some(if (context.isDefined) "Invitation" else "Login"),
                            invitationAccessId = context.map(_.id)
                          )
                          for {
                            saved <- requests.insert(persisted)
                            _ <- context.fold[DBIO[Unit]](DBIO.successful(()))(a =>
                                   accesses.update(a.copy(samlRequestId = Some(saved.id)), a.generation).flatMap {
                                     case true  => DBIO.successful(())
                                     case false => DBIO.failed(new IllegalStateException("SAML context changed during locked start"))
                                   }
                                 )
                          } yield Some(saved)
                        }
                      }
                  case _ => DBIO.successful(None)
                }
    } yield result
    cookie match {
      case Some(value) =>
        invitationAccess match {
          case None => DBIO.successful(None)
          case Some(accessService) =>
            accessService.samlStartAction(value, origin, csrf, auth.toVector.map(_.id), extraOrgs) { (record, context) =>
              if (record.invitation.organizationId == organizationId) save(Some(context)) else DBIO.successful(None)
            }
        }
      case None =>
        for {
          _ <- DBIO.sequence(
                 auth.toVector
                   .map(_.id)
                   .distinct
                   .sortBy(_.toString)
                   .map(id => sql"""SELECT id::text FROM "user" WHERE id=${id.toString}::uuid FOR UPDATE""".as[String])
               )
          _ <- DBIO.sequence(
                 (extraOrgs :+ organizationId).distinct
                   .sortBy(_.toString)
                   .map(id => sql"SELECT id::text FROM organization WHERE id=${id.toString}::uuid FOR UPDATE".as[String])
               )
          result <- save(None)
        } yield result
    }
  }

  private[epoca] def exchangeAction(
    token: SamlOneTimeToken,
    organizationSlug: OrganizationSlug,
    nonce: Option[String]
  ): DBIO[CallbackResult] = requests.find(token).flatMap {
    case Some(request) if !request.used && nonce.exists(n => request.browserNonceHash.exists(h => secrets.matches(n, h.toArray))) =>
      (request.verifiedIssuer, request.verifiedSubject, request.verifiedCanonicalEmail, request.verifiedAt) match {
        case (Some(issuer), Some(subject), Some(email), Some(at)) if !at.isAfter(clock.instant()) && !at.isBefore(request.createdAt) =>
          val context = SamlAuthContext(subject, Map("email" -> List(email)), issuer)
          locked(request, context).flatMap {
            case Some(state)
                if state.organization.slug == organizationSlug && state.config.idpEntityId == issuer &&
                  !request.createdAt.isAfter(clock.instant()) && request.isValid(clock.instant(), state.config.validFor) =>
              if (request.purpose.contains("Invitation")) exchangeContinuation(state, context)
              else exchangeSession(state, context)
            case _ => DBIO.successful(Denied)
          }
        case _ => DBIO.successful(Denied)
      }
    case _ => DBIO.successful(Denied)
  }

  /** Execute as a standalone transaction: owns the same canonical lock order as callback exchange. Read-only; never renews evidence or cookies. */
  private[epoca] def classifyContinuationAction(cookie: String): DBIO[Option[String]] = {
    val hash = pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest(secrets.hash(cookie))
    accesses.findByCookieHash(hash).flatMap {
      case Some(access) =>
        access.samlRequestId.fold[DBIO[Option[String]]](DBIO.successful(None)) { id =>
          requests.find(SamlRequestId(id)).flatMap {
            case Some(request) if request.used && request.token.isDefined =>
              (request.verifiedIssuer, request.verifiedSubject, request.verifiedCanonicalEmail) match {
                case (Some(issuer), Some(subject), Some(email)) =>
                  val context = SamlAuthContext(subject, Map("email" -> List(email)), issuer)
                  locked(request, context).flatMap {
                    case Some(state) if state.access.exists(_.cookieHash == hash) && continuationProof(state, context) =>
                      continuationNextAction(state).map(Some(_))
                    case _ => DBIO.successful(None)
                  }
                case _ => DBIO.successful(None)
              }
            case _ => DBIO.successful(None)
          }
        }
      case _ => DBIO.successful(None)
    }
  }

  private def continuationProof(state: Locked, context: SamlAuthContext): Boolean =
    invitationAccess.isDefined && state.request.purpose.contains("Invitation") && state.config.idpEntityId == context.issuer &&
      state.invitation.exists(pending) && state.request.verifiedAt.exists(at =>
        !at.isAfter(clock.instant()) && !at.isBefore(state.request.createdAt) && at.isAfter(clock.instant().minusSeconds(300)) &&
          state.access.exists(
            _.proof.saml
              .contains(VerifiedSamlProof(state.organization.id, state.request.trustEpoch.get, context.issuer, context.subject, context.email, at))
          )
      )

  private def currentRecipientMatches(
    state: Locked,
    userId: UserId,
    proof: AccountAuthentication
  ): DBIO[Boolean] = {
    val record = state.invitation.get
    val confirmedContact = state.resolution match {
      case Resolution.Single(user, owned) if user.id == userId && record.invitation.identityType == IdentityType.MailAddress =>
        owned.exists(i =>
          i.`type` == IdentityType.MailAddress && i.confirmed && record.canonicalEmail.contains(EmailCanonicalization.canonical(i.value))
        )
      case _ => false
    }
    if (confirmedContact) DBIO.successful(true)
    else
      proof.evidence match {
        // priorAccount already revalidated this original proof against current organization/config/binding state.
        case b: BoundSaml if b.organizationId == record.invitation.organizationId && record.invitation.identityType == IdentityType.MailAddress =>
          sql"SELECT 1 FROM saml_identity_binding WHERE organization_id=${b.organizationId.toString}::uuid AND trust_epoch=${b.trustEpoch} AND issuer=${b.issuer} AND subject=${b.subject} AND user_id=${userId.toString}::uuid AND canonical_email=${EmailCanonicalization
              .canonical(record.invitation.identityValue)} FOR SHARE".as[Int].map(_.nonEmpty)
        case _ => DBIO.successful(false)
      }
  }

  private def continuationNextAction(state: Locked): DBIO[String] = {
    val access = state.access.get
    priorAccount(state, independentOnly = false).flatMap {
      case Some((userId, proof)) if access.verifiedUserId.contains(userId) =>
        currentRecipientMatches(state, userId, proof).map(matches => if (matches) "Accept" else "SignIn")
      case _ =>
        state.resolution match {
          case Resolution.Empty if access.verifiedUserId.isEmpty && state.binding.isEmpty =>
            DBIO.successful(if (access.proof.emailVerifiedAt.exists(at => !at.isAfter(clock.instant()))) "Accept" else "VerifyLegacySso")
          case _ => DBIO.successful("SignIn")
        }
    }
  }

  private def exchangeContinuation(state: Locked, context: SamlAuthContext): DBIO[CallbackResult] =
    (invitationAccess, state.invitation, state.access) match {
      case (Some(accessService), Some(record), Some(access)) if continuationProof(state, context) =>
        continuationNextAction(state).flatMap { nextAction =>
          requests.consume(state.request.id, state.request.token.get).flatMap {
            case false => DBIO.successful(Denied)
            case true =>
              val recipient = InvitationAccessService.Recipient(
                InvitationAccessService.SafeOrganization(state.organization.name, state.organization.slug),
                state.resolution,
                ssoEnabled = true,
                candidate(state.resolution).toSet ++ access.verifiedUserId
              )
              accessService.rotateSamlCookieLocked(record, access, recipient, nextAction).flatMap {
                case Some(grant) => DBIO.successful(Continuation(state.organization.slug, grant))
                case None        => DBIO.failed(new IllegalStateException("SAML context changed during locked callback exchange"))
              }
          }
        }
      case _ => DBIO.successful(Denied)
    }

  private def exchangeSession(state: Locked, context: SamlAuthContext): DBIO[CallbackResult] = state.request.userId match {
    case Some(userId) if state.members.exists(m => m.userId == userId && m.archivedAt.isEmpty) =>
      val proof = state.request.accountAuthentication
      val valid = (state.binding, proof) match {
        case (Some(binding), Some(evidence))
            if binding.userId == userId && evidence.authenticatedAt == state.request.verifiedAt.get &&
              evidence.evidence == BoundSaml(state.organization.id, state.request.trustEpoch.get, context.issuer, context.subject) =>
          authentication.validate(
            AuthContext(userId, UserIsSuperAdmin(false), Seq.empty, Set.empty, Seq.empty, evidence.authenticatedAt, fresh = false, Some(evidence)),
            clock.instant()
          )
        case _ => DBIO.successful(false)
      }
      valid.flatMap {
        case false => DBIO.successful(Denied)
        case true =>
          for {
            user       <- users.find(UserFilters(id = Some(userId)))
            owned      <- identities.list(UserIdentityFilters(userId = Some(userId)))
            allMembers <- memberships.list(OrganizationUserFilters(organizationId = Some(state.organization.id), userId = Some(userId)))
            result <- user match {
                        case Some(value) if value.accountType == UserAccountType.Human =>
                          requests
                            .consume(state.request.id, state.request.token.get)
                            .map(consumed =>
                              if (consumed)
                                Session(
                                  pl.iterators.epoca.user.domain.UserWithIdentities(value, owned),
                                  allMembers,
                                  proof,
                                  state.request.verifiedAt.get,
                                  Some(
                                    WorkspacePhotoProof(
                                      state.organization.id,
                                      state.request.trustEpoch.get,
                                      context.issuer,
                                      context.subject,
                                      EmailCanonicalization.canonical(context.email)
                                    )
                                  )
                                )
                              else Denied
                            )
                        case _ => DBIO.successful(Denied)
                      }
          } yield result
      }
    case _ => DBIO.successful(Denied)
  }

  private case class Locked(
    organization: Organization,
    config: SamlConfig,
    request: SamlRequest,
    resolution: Resolution,
    binding: Option[SamlIdentityBinding],
    boundUser: Option[User],
    members: Seq[OrganizationUser],
    invitation: Option[Record],
    access: Option[Access])

  private def proofOrganizations(request: SamlRequest): Vector[OrganizationId] = request.accountAuthentication.toVector.flatMap(_.evidence match {
    case bound: BoundSaml => Vector(bound.organizationId)
    case _                => Vector.empty
  })
  private def candidate(resolution: Resolution): Vector[UserId] = resolution match {
    case Resolution.Single(user, _) => Vector(user.id)
    case _                          => Vector.empty
  }
  private def pending(record: Record): Boolean = record.protocolVersion == 2 && record.invitation.acceptAt.isEmpty &&
    record.invitation.rejectAt.isEmpty && record.invitation.archivedAt.isEmpty &&
    record.invitation.expireAt.exists(t => (t: Instant).isAfter(clock.instant()))

  private def locked(request: SamlRequest, context: SamlAuthContext): DBIO[Option[Locked]] = (request.organizationId, request.trustEpoch) match {
    case (Some(organizationId), Some(epoch))
        if epoch > 0 && request.browserNonceHash.exists(_.size == 32) &&
          ((request.purpose.contains("Login") && request.invitationAccessId.isEmpty) ||
            (request.purpose.contains("Invitation") && request.invitationAccessId.isDefined)) =>
      val email = EmailCanonicalization.canonical(context.email)
      val key   = SamlBindingKey(organizationId, epoch, context.issuer, context.subject)
      for {
        _           <- emails.lock(email)
        initial     <- emails.resolve(email)
        bindingHint <- bindings.find(key)
        accessHint  <- request.invitationAccessId.fold[DBIO[Option[Access]]](DBIO.successful(None))(accesses.findById)
        ids = (candidate(initial) ++ request.userId ++ bindingHint.map(_.userId) ++ accessHint.flatMap(_.verifiedUserId)).distinct.sortBy(_.toString)
        _ <- DBIO.sequence(ids.map(id => sql"""SELECT id::text FROM "user" WHERE id=${id.toString}::uuid FOR UPDATE""".as[String]))
        _ <- DBIO.sequence(
               (proofOrganizations(request) :+ organizationId).distinct
                 .sortBy(_.toString)
                 .map(id => sql"SELECT id::text FROM organization WHERE id=${id.toString}::uuid FOR UPDATE".as[String])
             )
        organization <- organizations.find(OrganizationFilters(id = Some(organizationId)))
        config       <- configs.find(organizationId)
        currentEpoch <- configs.trustEpoch(organizationId)
        binding      <- bindings.lock(key)
        boundUser <-
          binding.fold[DBIO[Option[User]]](DBIO.successful(None))(b => users.find(UserFilters(id = Some(b.userId), includeArchived = true)))
        memberRows <-
          DBIO.sequence(
            ids.map(id =>
              memberships
                .find(OrganizationUserFilters(organizationId = Some(organizationId), userId = Some(id), includeArchived = true, forUpdate = true))
            )
          )
        invitationHint <- invitations.findPendingByEmail(organizationId, email)
        invitation     <- invitationHint.fold[DBIO[Option[Record]]](DBIO.successful(None))(i => invitations.lockInvitation(i.invitation.id))
        access         <- request.invitationAccessId.fold[DBIO[Option[Access]]](DBIO.successful(None))(accesses.lockById)
        current        <- requests.lock(request.id)
        resolution     <- emails.resolve(email)
      } yield for {
        org <- organization
        cfg <- config if cfg.isEnabled && currentEpoch.contains(epoch)
        req <- current if req == request && candidate(resolution).forall(ids.contains) && binding.forall(b => ids.contains(b.userId))
        if resolution != Resolution.Ambiguous && (resolution match {
          case Resolution.Single(user, _) => user.archivedAt.isEmpty && user.accountType == UserAccountType.Human
          case _                          => true
        })
        if binding.forall(b =>
          b.canonicalEmail == email && boundUser.exists(u => u.archivedAt.isEmpty && u.accountType == UserAccountType.Human) &&
            candidate(resolution).forall(_ == b.userId)
        )
        if request.invitationAccessId.isEmpty || (for {
          record <- invitation
          a      <- access
        } yield pending(record) && a.invitationId == record.invitation.id && a.generation == record.generation && a.completedAt.isEmpty &&
          a.expiresAt.isAfter(clock.instant()) && a.samlRequestId.contains(request.id)).contains(true)
      } yield Locked(org, cfg, req, resolution, binding, boundUser, memberRows.flatten, invitation, access)
    case _ => DBIO.successful(None)
  }

  private def currentRequest(
    state: Locked,
    parsedConfig: SamlConfig,
    context: SamlAuthContext
  ): Boolean = {
    val request = state.request
    !request.used && request.token.isEmpty && !request.createdAt.isAfter(clock.instant()) && request.isValid(
      clock.instant(),
      state.config.validFor
    ) &&
    request.verifiedAt.isEmpty && request.verifiedIssuer.isEmpty && request.verifiedSubject.isEmpty && request.verifiedCanonicalEmail.isEmpty &&
    parsedConfig.organizationId == state.organization.id && state.config.idpCert == parsedConfig.idpCert &&
    state.config.idpEntityId == parsedConfig.idpEntityId && state.config.idpSsoUrl == parsedConfig.idpSsoUrl &&
    context.issuer == state.config.idpEntityId && context.subject.trim.nonEmpty &&
    UserIdentityValue.isValid(IdentityType.MailAddress, UserIdentityValue(EmailCanonicalization.canonical(context.email)))
  }
  private def boundProof(
    request: SamlRequest,
    context: SamlAuthContext,
    verifiedAt: Instant
  ): AccountAuthentication =
    AccountAuthentication(verifiedAt, BoundSaml(request.organizationId.get, request.trustEpoch.get, context.issuer, context.subject))

  private def persist(
    request: SamlRequest,
    context: SamlAuthContext,
    userId: Option[UserId],
    proof: Option[AccountAuthentication],
    access: Option[Access],
    verifiedAt: Instant
  ): DBIO[Option[SamlRequest]] = {
    val updated = request.copy(
      token = Some(SamlOneTimeToken(UUID.randomUUID())),
      userId = userId,
      accountAuthentication = proof,
      purpose = Some(if (access.isDefined) "Invitation" else "Login"),
      invitationAccessId = access.map(_.id),
      verifiedIssuer = Some(context.issuer),
      verifiedSubject = Some(context.subject),
      verifiedCanonicalEmail = Some(EmailCanonicalization.canonical(context.email)),
      verifiedAt = Some(verifiedAt)
    )
    requests.update(updated).flatMap {
      case Some(saved) => DBIO.successful(Some(saved))
      case None        => DBIO.failed(new IllegalStateException("SAML request disappeared during locked completion"))
    }
  }
  private def priorAccount(state: Locked, independentOnly: Boolean = true): DBIO[Option[(UserId, AccountAuthentication)]] =
    (state.request.userId, state.request.accountAuthentication) match {
      case (Some(userId), Some(proof)) if candidate(state.resolution).contains(userId) && (proof.evidence match {
            case _: AccountAuthentication.Credential => true
            case bound: BoundSaml                    => !independentOnly && bound.organizationId == state.organization.id
          }) =>
        val auth = AuthContext(userId, UserIsSuperAdmin(false), Seq.empty, Set.empty, Seq.empty, proof.authenticatedAt, fresh = false, Some(proof))
        authentication.validate(auth, clock.instant()).map(valid => Option.when(valid)(userId -> proof))
      case _ => DBIO.successful(None)
    }

  private def continuation(
    state: Locked,
    context: SamlAuthContext,
    record: Record,
    verifiedAt: Instant
  ): DBIO[Option[SamlRequest]] = invitationAccess match {
    case Some(accessService) if pending(record) && record.firstName.exists(_.trim.nonEmpty) && record.lastName.exists(_.trim.nonEmpty) =>
      val known   = state.binding.map(binding => binding.userId -> boundProof(state.request, context, verifiedAt))
      val account = known.fold(priorAccount(state))(value => DBIO.successful(Some(value)))
      account.flatMap { trusted =>
        val proof = VerifiedSamlProof(
          state.organization.id,
          state.request.trustEpoch.get,
          context.issuer,
          context.subject,
          EmailCanonicalization.canonical(context.email),
          verifiedAt
        )
        accessService.trustedSamlContextLocked(record, proof, state.request.id, state.access, trusted.map(_._1)).flatMap {
          case Some(access) => persist(state.request, context, trusted.map(_._1), trusted.map(_._2), Some(access), verifiedAt)
          case None         => DBIO.successful(None)
        }
      }
    case _ => DBIO.successful(None)
  }

  private def bindAuthenticatedMember(
    state: Locked,
    context: SamlAuthContext,
    verifiedAt: Instant
  ): DBIO[Option[SamlRequest]] =
    // Trust rotation invalidates old bindings. Only independently revalidated account authentication can establish a new one.
    priorAccount(state).flatMap {
      case Some((userId, _)) =>
        identities
          .list(
            UserIdentityFilters(
              userId = Some(userId),
              `type` = Some(IdentityType.MailAddress),
              confirmed = Some(UserIdentityConfirmed(true)),
              forUpdate = true
            )
          )
          .flatMap { owned =>
            if (!owned.exists(i => EmailCanonicalization.canonical(i.value) == EmailCanonicalization.canonical(context.email)))
              DBIO.successful(None)
            else
              for {
                _ <- bindings.insert(
                       SamlIdentityBinding(
                         SamlBindingKey(state.organization.id, state.request.trustEpoch.get, context.issuer, context.subject),
                         userId,
                         EmailCanonicalization.canonical(context.email),
                         verifiedAt,
                         Json.obj("source" -> Json.fromString("authenticated-member"))
                       )
                     )
                result <- persist(state.request, context, Some(userId), Some(boundProof(state.request, context, verifiedAt)), None, verifiedAt)
              } yield result
          }
      case _ => DBIO.successful(None)
    }

  private[epoca] def authorizeAction(
    request: SamlRequest,
    parsedConfig: SamlConfig,
    context: SamlAuthContext
  ): DBIO[Option[SamlRequest]] =
    locked(request, context).flatMap {
      case Some(state) if currentRequest(state, parsedConfig, context) =>
        val verifiedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val active     = state.members.filter(_.archivedAt.isEmpty).map(_.userId).toSet
        state.binding match {
          case Some(binding) if active(binding.userId) =>
            persist(request, context, Some(binding.userId), Some(boundProof(request, context, verifiedAt)), None, verifiedAt)
          case _ =>
            state.resolution match {
              case Resolution.Single(user, _) if active(user.id) && state.binding.isEmpty =>
                // Organization membership and an asserted email never authenticate a global account.
                bindAuthenticatedMember(state, context, verifiedAt)
              case _ =>
                state.invitation match {
                  case Some(record) => continuation(state, context, record, verifiedAt)
                  // An organization's IdP cannot verify ownership of a global email address.
                  // New accounts must use an invitation with independent mailbox verification.
                  case _ => DBIO.successful(None)
                }
            }
        }
      case _ => DBIO.successful(None)
    }
}

object SamlInvitationService {
  sealed trait CallbackResult
  case object Denied extends CallbackResult
  final case class Continuation(organizationSlug: String, grant: InvitationAccessService.Granted) extends CallbackResult {
    override def toString: String = "SamlContinuation(<redacted>)"
  }
  final case class Session(
    user: pl.iterators.epoca.user.domain.UserWithIdentities,
    memberships: Seq[OrganizationUser],
    authentication: Option[AccountAuthentication],
    authenticatedAt: Instant,
    photoProof: Option[WorkspacePhotoProof] = None)
      extends CallbackResult
}
