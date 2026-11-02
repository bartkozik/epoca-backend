package pl.iterators.epoca.saml.services

import io.circe.Json
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories._
import pl.iterators.epoca.invitation.services.{InvitationAccessService, InvitationBrowserSecurity, InvitationSecretService}
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlBindingKey, SamlConfig, SamlIdentityBinding, SamlRequest}
import pl.iterators.epoca.saml.repositories._
import pl.iterators.epoca.saml.services.SamlInvitationService.{Continuation, Denied, Session}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories._
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID

class SamlInvitationServiceSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  "Workspace photo authorization" should {
    "reject membership removal, changed binding email, disabled SAML and rotated trust before storing a fetched photo" in withDatabase {
      for {
        f     <- fixture(existing = Some(IdentityType.MailAddress), activeMember = true, bound = true)
        epoch <- configs.trustEpoch(f.organization.id)
        proof  = WorkspacePhotoProof(f.organization.id, epoch.get, f.context.issuer, f.context.subject, f.context.email)
        userId = f.user.get.id
        _            <- sql"""SELECT id::text FROM "user" WHERE id=${userId.toString}::uuid FOR UPDATE""".as[String]
        valid        <- WorkspaceAvatarSyncService.stillAuthorized(userId, proof)
        _            <- sqlu"UPDATE saml_identity_binding SET canonical_email='changed@example.test' WHERE user_id=${userId.toString}::uuid"
        changedEmail <- WorkspaceAvatarSyncService.stillAuthorized(userId, proof)
        _            <- sqlu"UPDATE saml_identity_binding SET canonical_email=${proof.canonicalEmail} WHERE user_id=${userId.toString}::uuid"
        _ <-
          sqlu"UPDATE organization_user SET archived_at=now() WHERE organization_id=${f.organization.id.toString}::uuid AND user_id=${userId.toString}::uuid"
        removedMember <- WorkspaceAvatarSyncService.stillAuthorized(userId, proof)
        _ <-
          sqlu"UPDATE organization_user SET archived_at=NULL WHERE organization_id=${f.organization.id.toString}::uuid AND user_id=${userId.toString}::uuid"
        _        <- configs.upsert(f.config.copy(isEnabled = SamlIsEnabled(false)))
        disabled <- WorkspaceAvatarSyncService.stillAuthorized(userId, proof)
        _        <- configs.upsert(f.config.copy(idpCert = SamlIdpCert("rotated-certificate")))
        rotated  <- WorkspaceAvatarSyncService.stillAuthorized(userId, proof)
      } yield {
        valid must beTrue
        Seq(changedEmail, removedMember, disabled, rotated) mustEqual Seq(false, false, false, false)
      }
    }
  }
  private val now            = Instant.parse("2026-10-02T10:00:00Z")
  private val clock          = Clock.fixed(now, ZoneOffset.UTC)
  private val requests       = new SamlRequestRepositoryImpl
  private val configs        = new SamlConfigRepositoryImpl
  private val bindings       = new SamlIdentityBindingRepositoryImpl
  private val invitations    = new InvitationOnboardingRepositoryImpl
  private val accesses       = new InvitationAccessRepositoryImpl
  private val emails         = new EmailIdentityRepositoryImpl
  private val organizations  = new OrganizationRepositoryImpl
  private val users          = new UserRepositoryImpl
  private val identities     = new UserIdentityRepositoryImpl
  private val memberships    = new OrganizationUserRepositoryImpl
  private val passwords      = new UserPasswordRepositoryImpl
  private val authentication = new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl, identities, passwords)
  private val secrets        = new InvitationSecretService
  private val browser        = new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", false, false, secrets)
  private def service(
    enabled: Boolean = true,
    testClock: Clock = clock,
    requestRepository: SamlRequestRepository = requests
  ): SamlInvitationService = new SamlInvitationService(
    requestRepository,
    configs,
    bindings,
    invitations,
    accesses,
    emails,
    organizations,
    users,
    identities,
    memberships,
    authentication,
    secrets,
    testClock,
    Option.when(enabled)(new InvitationAccessService(null, invitations, accesses, emails, secrets, browser, null, testClock))
  )

  private case class Fixture(
    actor: UserId,
    organization: Organization,
    config: SamlConfig,
    request: SamlRequest,
    context: SamlAuthContext,
    invitation: Option[Record],
    user: Option[User]) {
    def authorize(enabled: Boolean = true): DBIO[Option[SamlRequest]] = service(enabled).authorizeAction(request, config, context)
    def counts: DBIO[(Int, Int, Int)] = for {
      accounts <- sql"SELECT count(*) FROM user_identity WHERE value=${context.email.toString}".as[Int].head
      members  <- sql"SELECT count(*) FROM organization_user WHERE organization_id=${organization.id.toString}::uuid".as[Int].head
      trusted  <- sql"SELECT count(*) FROM saml_identity_binding WHERE organization_id=${organization.id.toString}::uuid".as[Int].head
    } yield (accounts, members, trusted)
  }
  private def fixture(
    state: Option[String] = Some("Pending"),
    allowInit: Boolean = false,
    existing: Option[IdentityType] = None,
    activeMember: Boolean = false,
    bound: Boolean = false
  ): DBIO[Fixture] = for {
    actor        <- withUser()
    organization <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
    email = s"saml-${UUID.randomUUID()}@example.test"
    config <- configs.upsert(
                allGenerators[SamlConfig].normal.generate.copy(
                  organizationId = organization.id,
                  idpEntityId = SamlIdpEntityId("https://idp.example.test/entity"),
                  idpCert = SamlIdpCert("synthetic-cert"),
                  idpSsoUrl = SamlIdpSsoUrl("https://idp.example.test/sso"),
                  validFor = SamlValidityDuration(Duration.ofMinutes(5)),
                  allowAccountInit = SamlAllowAccountInitialization(allowInit),
                  isEnabled = SamlIsEnabled(true)
                )
              )
    user <- existing match {
              case Some(kind) =>
                for {
                  u <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
                  identity <- identities.insert(
                                allGenerators[UserIdentity].normal.generate
                                  .copy(userId = u.id, `type` = kind, value = UserIdentityValue(email), confirmed = UserIdentityConfirmed(true))
                              )
                  _ <- if (kind == IdentityType.MailAddress)
                         passwords.upsert(UserPassword(identity.id, PasswordHash("existing-password-hash"))).map(_ => ())
                       else DBIO.successful(())
                } yield Some(u)
              case None => DBIO.successful(None)
            }
    context =
      SamlAuthContext("stable-subject", Map("email" -> List(email), "firstName" -> List("IdP"), "lastName" -> List("Person")), config.idpEntityId)
    _ <- if (activeMember)
           memberships
             .insertPrepared(OrganizationUser(OrganizationUserId.random, organization.id, user.get.id, Seq(OrganizationUserRole.Manager), None))
             .map(_ => ())
         else DBIO.successful(())
    _ <- if (bound)
           bindings.insert(
             SamlIdentityBinding(
               SamlBindingKey(organization.id, 1L, context.issuer, context.subject),
               user.get.id,
               email,
               now.minusSeconds(60),
               Json.obj()
             )
           )
         else DBIO.successful(())
    invitation <- state match {
                    case None => DBIO.successful(None)
                    case Some(value) =>
                      val i = allGenerators[Invitation].normal.generate.copy(
                        organizationId = organization.id,
                        createdBy = actor.id,
                        modifiedBy = actor.id,
                        identityType = IdentityType.MailAddress,
                        identityValue = UserIdentityValue(email),
                        roles = Seq(OrganizationUserRole.Manager),
                        expireAt = Some(InvitationExpireAt(if (value == "Expired") now.minusSeconds(1) else now.plusSeconds(3600))),
                        acceptAt = Option.when(value == "Accepted")(InvitationAcceptAt(now.minusSeconds(30))),
                        rejectAt = None,
                        archivedAt = Option.when(value == "Cancelled")(InvitationArchivedAt(now.minusSeconds(30)))
                      )
                      invitations
                        .insert(
                          Record(
                            i,
                            2,
                            Some("Invited"),
                            Some("Name"),
                            0,
                            1,
                            Some(Digest(secrets.hash(secrets.generate()))),
                            if (value == "Accepted") user.map(_.id) else None,
                            Some("en"),
                            None
                          )
                        )
                        .flatMap(_ => invitations.find(i.id))
                  }
    request <- requests.insert(
                 SamlRequest(
                   SamlRequestId("_" + UUID.randomUUID()),
                   None,
                   None,
                   SamlRequestUsed(false),
                   SamlRequestCreatedAt(now),
                   organizationId = Some(organization.id),
                   trustEpoch = Some(1L),
                   purpose = Some("Login"),
                   browserNonceHash = Some(secrets.hash("synthetic-browser-nonce").toVector)
                 )
               )
  } yield Fixture(actor.id, organization, config, request, context, invitation, user)

  "active member binding after trust rotation" should {
    "reject stored SAML authority as independent proof for a new subject binding" in withDatabase {
      import pl.iterators.epoca.auth.domain.AccountAuthentication
      import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
      for {
        f <- fixture(state = None, existing = Some(IdentityType.MailAddress), activeMember = true, bound = true)
        proof   = AccountAuthentication(now.minusSeconds(30), BoundSaml(f.organization.id, 1L, f.context.issuer, f.context.subject))
        context = f.context.copy(id = "different-subject")
        next <-
          requests.insert(f.request.copy(id = SamlRequestId("_" + UUID.randomUUID()), userId = f.user.map(_.id), accountAuthentication = Some(proof)))
        issued <- service().authorizeAction(next, f.config, context)
        linked <- bindings.find(SamlBindingKey(f.organization.id, 1L, context.issuer, context.subject))
      } yield {
        issued must beNone
        linked must beNone
      }
    }
    Seq(
      "Missing",
      "Stale",
      "Future",
      "WrongUser",
      "Unconfirmed",
      "UnconfirmedRecipient",
      "PasswordRemoved",
      "OldBinding",
      "Nonmember",
      "ArchivedMember",
      "ArchivedUser"
    ).foreach { invalid =>
      s"reject $invalid evidence or membership after trust rotation without creating a binding" in withDatabase {
        import pl.iterators.epoca.auth.domain.AccountAuthentication
        import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
        for {
          f       <- fixture(state = None, existing = Some(IdentityType.MailAddress), activeMember = invalid != "Nonmember", bound = true)
          rotated <- configs.upsert(f.config.copy(idpCert = SamlIdpCert("renewed-certificate")))
          epoch   <- configs.trustEpoch(f.organization.id)
          owned   <- identities.list(UserIdentityFilters(userId = f.user.map(_.id)))
          at = invalid match {
                 case "Stale"  => now.minusSeconds(301)
                 case "Future" => now.plusSeconds(1)
                 case _        => now.minusSeconds(30)
               }
          credential <- if (invalid == "UnconfirmedRecipient") {
                          for {
                            other <-
                              identities.insert(
                                owned.head
                                  .copy(id = UserIdentityId(UUID.randomUUID()), value = UserIdentityValue(s"other-${UUID.randomUUID()}@example.test"))
                              )
                            _ <- passwords.upsert(UserPassword(other.id, PasswordHash("other-password")))
                          } yield other
                        } else DBIO.successful(owned.head)
          proof = AccountAuthentication(
                    at,
                    if (invalid == "OldBinding")
                      BoundSaml(f.organization.id, 1L, f.context.issuer, f.context.subject)
                    else Credential(CredentialMethod.Password, credential.id)
                  )
          _ <- invalid match {
                 case "Unconfirmed" | "UnconfirmedRecipient" =>
                   identities.update(owned.head.copy(confirmed = UserIdentityConfirmed(false))).map(_ => ())
                 case "PasswordRemoved" => sqlu"DELETE FROM user_password WHERE user_identity_id=${owned.head.id.toString}::uuid".map(_ => ())
                 case "ArchivedMember" =>
                   sqlu"UPDATE organization_user SET archived_at=${now.toString}::timestamptz WHERE organization_id=${f.organization.id.toString}::uuid"
                     .map(_ => ())
                 case "ArchivedUser" =>
                   sqlu"""UPDATE "user" SET archived_at=${now.toString}::timestamptz WHERE id=${f.user.get.id.toString}::uuid""".map(_ => ())
                 case _ => DBIO.successful(())
               }
          next <- requests.insert(
                    f.request.copy(
                      id = SamlRequestId("_" + UUID.randomUUID()),
                      trustEpoch = epoch,
                      userId = Some(if (invalid == "WrongUser") f.actor else f.user.get.id),
                      accountAuthentication = Option.unless(invalid == "Missing")(proof)
                    )
                  )
          issued <- service().authorizeAction(next, rotated, f.context)
          bound  <- bindings.find(SamlBindingKey(f.organization.id, epoch.get, f.context.issuer, f.context.subject))
          saved  <- requests.find(next.id)
        } yield {
          issued must beNone
          bound must beNone
          saved.flatMap(_.token) must beNone
        }
      }
    }
    "rebind a password member with fresh original authentication and preserve roles" in withDatabase {
      import pl.iterators.epoca.auth.domain.AccountAuthentication
      import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
      val actual = service(enabled = false)
      for {
        f       <- fixture(state = None, existing = Some(IdentityType.MailAddress), activeMember = true, bound = true)
        before  <- f.authorize()
        rotated <- configs.upsert(f.config.copy(idpCert = SamlIdpCert("renewed-certificate")))
        epoch   <- configs.trustEpoch(f.organization.id)
        owned   <- identities.list(UserIdentityFilters(userId = f.user.map(_.id)))
        proof = AccountAuthentication(now.minusSeconds(30), Credential(CredentialMethod.Password, owned.head.id))
        next  = f.request.copy(id = SamlRequestId("_" + UUID.randomUUID()), trustEpoch = epoch)
        auth =
          pl.iterators.epoca.auth.domain
            .AuthContext(f.user.get.id, UserIsSuperAdmin(false), Seq.empty, Set.empty, Seq.empty, proof.authenticatedAt, fresh = false, Some(proof))
        started <- actual.startAction(next, rotated, Some(auth), None, None, None)
        issued  <- actual.authorizeAction(started.get, rotated, f.context)
        bound   <- bindings.find(SamlBindingKey(f.organization.id, epoch.get, f.context.issuer, f.context.subject))
        session <- issued.fold[DBIO[SamlInvitationService.CallbackResult]](DBIO.successful(Denied))(r =>
                     service().exchangeAction(r.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
                   )
        replay <- issued.fold[DBIO[SamlInvitationService.CallbackResult]](DBIO.successful(Denied))(r =>
                    service().exchangeAction(r.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
                  )
        following <- requests.insert(next.copy(id = SamlRequestId("_" + UUID.randomUUID())))
        nextLogin <- actual.authorizeAction(following, rotated, f.context)
        counts    <- f.counts
      } yield {
        before must beSome
        issued.flatMap(_.userId) mustEqual f.user.map(_.id)
        bound.map(_.userId) mustEqual f.user.map(_.id)
        session must beLike { case Session(user, members, authentication, _, photoProof) =>
          user.id mustEqual f.user.get.id
          photoProof must beSome(WorkspacePhotoProof(f.organization.id, epoch.get, f.context.issuer, f.context.subject, f.context.email))
          members.find(_.organizationId == f.organization.id).get.roles mustEqual Seq(OrganizationUserRole.Manager)
          authentication.map(_.evidence) must beSome(BoundSaml(f.organization.id, epoch.get, f.context.issuer, f.context.subject))
        }
        replay mustEqual Denied
        nextLogin.flatMap(_.userId) mustEqual f.user.map(_.id)
        nextLogin.flatMap(_.accountAuthentication).map(_.evidence) must beSome(
          BoundSaml(f.organization.id, epoch.get, f.context.issuer, f.context.subject)
        )
        counts mustEqual ((1, 1, 2))
      }
    }
  }

  "linked continuation classification" should {
    "allow a current bound recipient only within the invitation organization" in withDatabase {
      import pl.iterators.epoca.auth.domain.AccountAuthentication
      import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
      for {
        f      <- fixture(existing = Some(IdentityType.MailAddress), bound = true)
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = result.asInstanceOf[Continuation].grant
        sameOrganization <- service().classifyContinuationAction(grant.cookie)
        other            <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        _                <- configs.upsert(f.config.copy(organizationId = other.id))
        _ <-
          memberships.insertPrepared(OrganizationUser(OrganizationUserId.random, other.id, f.user.get.id, Seq(OrganizationUserRole.Employee), None))
        _ <- bindings.insert(
               SamlIdentityBinding(
                 SamlBindingKey(other.id, 1L, f.context.issuer, "foreign-subject"),
                 f.user.get.id,
                 f.context.email,
                 now.minusSeconds(30),
                 Json.obj()
               )
             )
        foreignProof = AccountAuthentication(now.minusSeconds(30), BoundSaml(other.id, 1L, f.context.issuer, "foreign-subject"))
        foreignAuth  = pl.iterators.epoca.utils.AuthContextProvider.user.copy(id = f.user.get.id, accountAuthentication = Some(foreignProof))
        foreignValid        <- authentication.validate(foreignAuth, now)
        consumed            <- requests.find(f.request.id)
        _                   <- requests.update(consumed.get.copy(accountAuthentication = Some(foreignProof)))
        foreignOrganization <- service().classifyContinuationAction(grant.cookie)
        counts              <- f.counts
      } yield {
        grant.dto.nextAction mustEqual "Accept"
        sameOrganization must beSome("Accept")
        foreignValid must beTrue
        foreignOrganization must beSome("SignIn")
        counts mustEqual (1, 0, 1)
      }
    }
    "require a currently confirmed invited contact when prior login used a different credential" in withDatabase {
      import pl.iterators.epoca.auth.domain.AccountAuthentication
      import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
      for {
        f     <- fixture(existing = Some(IdentityType.MailAddress))
        owned <- identities.list(UserIdentityFilters(userId = f.user.map(_.id)))
        target = owned.head
        _ <- identities.update(target.copy(confirmed = UserIdentityConfirmed(false)))
        credential <-
          identities.insert(
            target.copy(id = UserIdentityId(UUID.randomUUID()), value = UserIdentityValue(s"credential-${UUID.randomUUID()}@example.test"))
          )
        _ <- passwords.upsert(UserPassword(credential.id, PasswordHash("other-confirmed-credential")))
        proof = AccountAuthentication(now.minusSeconds(30), Credential(CredentialMethod.Password, credential.id))
        captured  <- requests.update(f.request.copy(userId = f.user.map(_.id), accountAuthentication = Some(proof)))
        issued    <- service().authorizeAction(captured.get, f.config, f.context)
        exchanged <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = exchanged.asInstanceOf[Continuation].grant
        first     <- service().classifyContinuationAction(grant.cookie)
        _         <- identities.update(target)
        confirmed <- service().classifyContinuationAction(grant.cookie)
        _         <- identities.update(target.copy(confirmed = UserIdentityConfirmed(false)))
        revoked   <- service().classifyContinuationAction(grant.cookie)
      } yield {
        grant.dto.nextAction mustEqual "SignIn"
        first must beSome("SignIn")
        confirmed must beSome("Accept")
        revoked must beSome("SignIn")
      }
    }

    Seq("Expired", "Completed", "Generation", "Cancelled", "Epoch", "Disabled", "Subject", "StaleProof").foreach { changed =>
      s"reject $changed continuation on read-only reload" in withDatabase {
        for {
          f         <- fixture()
          issued    <- f.authorize()
          exchanged <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
          grant = exchanged.asInstanceOf[Continuation].grant
          before <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
          _ <- changed match {
                 case "Expired"   => accesses.revoke(before.get.id, now)
                 case "Completed" => accesses.update(before.get.copy(completedAt = Some(now)), 1).map(_ => ())
                 case "Generation" =>
                   sqlu"UPDATE invitation SET generation=generation+1 WHERE id=${f.invitation.get.invitation.id.toString}::uuid".map(_ => ())
                 case "Cancelled" =>
                   sqlu"UPDATE invitation SET archived_at=${now.toString}::timestamptz WHERE id=${f.invitation.get.invitation.id.toString}::uuid".map(
                     _ => ()
                   )
                 case "Epoch"    => configs.upsert(f.config.copy(idpCert = SamlIdpCert("changed"))).map(_ => ())
                 case "Disabled" => configs.upsert(f.config.copy(isEnabled = SamlIsEnabled(false))).map(_ => ())
                 case "Subject"  => requests.update(issued.get.copy(used = SamlRequestUsed(true), verifiedSubject = Some("other"))).map(_ => ())
                 case _          => DBIO.successful(())
               }
          current <- accesses.findById(before.get.id)
          action <- service(testClock = if (changed == "StaleProof") Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC) else clock)
                      .classifyContinuationAction(grant.cookie)
          after <- accesses.findById(before.get.id)
        } yield { action must beNone; after mustEqual current }
      }
    }

    "require mailbox verification for a new SAML recipient without mutating its context on reload" in withDatabase {
      for {
        f      <- fixture()
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = result.asInstanceOf[Continuation].grant
        before <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        next   <- service().classifyContinuationAction(grant.cookie)
        after  <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
      } yield { grant.dto.nextAction mustEqual "VerifyLegacySso"; next must beSome("VerifyLegacySso"); before mustEqual after }
    }
    "require independent account authentication for an unbound legacy SAML-only account even after mailbox verification" in withDatabase {
      for {
        f      <- fixture(existing = Some(IdentityType.Saml))
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = result.asInstanceOf[Continuation].grant
        before    <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        first     <- service().classifyContinuationAction(grant.cookie)
        _         <- accesses.update(before.get.copy(proof = before.get.proof.copy(emailVerifiedAt = Some(now))), before.get.generation)
        both      <- service().classifyContinuationAction(grant.cookie)
        unchanged <- requests.find(f.request.id)
        counts    <- f.counts
      } yield {
        grant.dto.nextAction mustEqual "SignIn"
        first must beSome("SignIn")
        both must beSome("SignIn")
        unchanged.get.used must beTrue
        counts mustEqual (1, 0, 0)
      }
    }
  }
  "SAML account takeover prevention" should {
    "reject a preissued unbound callback even when the user belongs to the asserting organization" in withDatabase {
      for {
        f     <- fixture(state = None, existing = Some(IdentityType.Saml), activeMember = true)
        other <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        _ <- memberships.insertPrepared(OrganizationUser(OrganizationUserId.random, other.id, f.user.get.id, Seq(OrganizationUserRole.Owner), None))
        legacy <- requests.update(
                    f.request.copy(
                      token = Some(SamlOneTimeToken(UUID.randomUUID())),
                      userId = f.user.map(_.id),
                      verifiedIssuer = Some(f.context.issuer),
                      verifiedSubject = Some(f.context.subject),
                      verifiedCanonicalEmail = Some(f.context.email),
                      verifiedAt = Some(now)
                    )
                  )
        result <- service().exchangeAction(legacy.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        saved  <- requests.find(f.request.id)
      } yield { result mustEqual Denied; saved.get.used must beFalse }
    }
    Seq("ForeignBinding", "HistoricalBinding", "OtherCredential").foreach { controlled =>
      s"require independent sign-in for legacy adoption with $controlled even after mailbox verification" in withDatabase {
        for {
          initial <- fixture(existing = Some(IdentityType.Saml))
          f <- if (controlled == "HistoricalBinding")
                 for {
                   config  <- configs.upsert(initial.config.copy(idpCert = SamlIdpCert("rotated-certificate")))
                   request <- requests.update(initial.request.copy(trustEpoch = Some(2L)))
                 } yield initial.copy(config = config, request = request.get)
               else DBIO.successful(initial)
          _ <- controlled match {
                 case "OtherCredential" =>
                   identities
                     .insert(
                       allGenerators[UserIdentity].normal.generate.copy(
                         userId = f.user.get.id,
                         `type` = IdentityType.MailAddress,
                         value = UserIdentityValue(s"other-${UUID.randomUUID()}@example.test"),
                         confirmed = UserIdentityConfirmed(true)
                       )
                     )
                     .map(_ => ())
                 case _ =>
                   for {
                     other <- if (controlled == "ForeignBinding")
                                withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
                              else DBIO.successful(f.organization)
                     _ <- bindings.insert(
                            SamlIdentityBinding(
                              SamlBindingKey(other.id, 1L, f.context.issuer, "attacker-subject"),
                              f.user.get.id,
                              f.context.email,
                              now.minusSeconds(60),
                              Json.obj()
                            )
                          )
                   } yield ()
               }
          issued <- f.authorize()
          result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
          grant = result.asInstanceOf[Continuation].grant
          context <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
          _       <- accesses.update(context.get.copy(proof = context.get.proof.copy(emailVerifiedAt = Some(now))), context.get.generation)
          next    <- service().classifyContinuationAction(grant.cookie)
        } yield { grant.dto.nextAction mustEqual "SignIn"; next must beSome("SignIn") }
      }
    }
    "allow a new SAML recipient to continue after independent mailbox verification" in withDatabase {
      for {
        f      <- fixture()
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = result.asInstanceOf[Continuation].grant
        context <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        _       <- accesses.update(context.get.copy(proof = context.get.proof.copy(emailVerifiedAt = Some(now))), context.get.generation)
        next    <- service().classifyContinuationAction(grant.cookie)
      } yield next must beSome("Accept")
    }
  }
  "trusted SAML start" should {
    "persist current organization epoch browser hash and prior validated account evidence" in withDatabase {
      for {
        f     <- fixture(existing = Some(IdentityType.MailAddress))
        owned <- identities.list(UserIdentityFilters(userId = f.user.map(_.id)))
        proof = pl.iterators.epoca.auth.domain.AccountAuthentication(
                  now.minusSeconds(30),
                  pl.iterators.epoca.auth.domain.AccountAuthentication
                    .Credential(pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod.Password, owned.head.id)
                )
        auth =
          pl.iterators.epoca.auth.domain
            .AuthContext(f.user.get.id, UserIsSuperAdmin(false), Seq.empty, Set.empty, Seq.empty, proof.authenticatedAt, false, Some(proof))
        started <- service().startAction(
                     f.request.copy(id = SamlRequestId("_" + UUID.randomUUID()), trustEpoch = None),
                     f.config,
                     Some(auth),
                     None,
                     None,
                     None
                   )
        saved <- started.fold[DBIO[Option[SamlRequest]]](DBIO.successful(None))(r => requests.find(r.id))
      } yield {
        saved.map(_.organizationId) mustEqual Some(Some(f.organization.id))
        saved.map(_.trustEpoch) mustEqual Some(Some(1L))
        saved.map(_.purpose) mustEqual Some(Some("Login"))
        saved.map(_.browserNonceHash) mustEqual Some(f.request.browserNonceHash)
        saved.flatMap(_.accountAuthentication) mustEqual Some(proof)
      }
    }
    "link invitation start only after exact Origin and CSRF checks while preserving context identity and TTL" in withDatabase {
      for {
        f         <- fixture()
        issued    <- f.authorize()
        exchanged <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        grant = exchanged.asInstanceOf[Continuation].grant
        previous <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        request = f.request.copy(id = SamlRequestId("_" + UUID.randomUUID()), trustEpoch = None)
        wrongOrigin <- service().startAction(request, f.config, None, Some(grant.cookie), Some("https://attacker.test"), Some(grant.dto.csrfToken))
        wrongCsrf   <- service().startAction(request, f.config, None, Some(grant.cookie), Some("https://app.example.test"), Some("wrong"))
        valid       <- service().startAction(request, f.config, None, Some(grant.cookie), Some("https://app.example.test"), Some(grant.dto.csrfToken))
        current     <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
      } yield {
        wrongOrigin must beNone
        wrongCsrf must beNone
        valid.flatMap(_.invitationAccessId) mustEqual previous.map(_.id)
        valid.flatMap(_.purpose) mustEqual Some("Invitation")
        current.map(_.samlRequestId) mustEqual Some(Some(request.id.toString))
        current.map(_.expiresAt) mustEqual previous.map(_.expiresAt)
        current.map(_.proof) mustEqual previous.map(_.proof)
      }
    }
    "fail closed for stale signing config and disabled invitation components while keeping ordinary SSO available" in withDatabase {
      for {
        f <- fixture(state = None)
        request = f.request.copy(id = SamlRequestId("_" + UUID.randomUUID()))
        stale <- service().startAction(request, f.config.copy(idpCert = SamlIdpCert("wrong")), None, None, None, None)
        invitation <-
          service(enabled = false).startAction(request, f.config, None, Some("opaque-context"), Some("https://app.example.test"), Some("csrf"))
        ordinary <- service(enabled = false).startAction(request, f.config, None, None, None, None)
      } yield { stale must beNone; invitation must beNone; ordinary must beSome }
    }
  }
  "trusted invitation ACS classification" should {
    "issue a limited Manager continuation without email exchange or JIT even when account initialization is disabled" in withDatabase {
      for {
        f      <- fixture()
        result <- service().authorizeAction(f.request, f.config, f.context.copy(attributes = Map("email" -> List(f.context.email.toString))))
        proof  <- sql"SELECT proof::text FROM invitation_access WHERE saml_request_id=${f.request.id.toString}".as[String].headOption
        counts <- f.counts
      } yield {
        result.flatMap(_.purpose) shouldEqual Some("Invitation")
        result.flatMap(_.invitationAccessId) must beSome
        result.flatMap(_.userId) shouldEqual None
        proof.map(_.contains("stable-subject")) shouldEqual Some(true)
        counts shouldEqual ((0, 0, 0))
      }
    }
    Seq("Expired", "Cancelled", "Accepted").foreach { state =>
      s"block $state invitation history before JIT when membership is absent" in withDatabase {
        for {
          f      <- fixture(state = Some(state), allowInit = true, existing = Option.when(state == "Accepted")(IdentityType.Saml))
          result <- f.authorize()
          counts <- f.counts
        } yield {
          result shouldEqual None
          counts._2 shouldEqual 0
          counts._3 shouldEqual 0
        }
      }
    }
    "deny unverified no-history JIT even when account initialization is enabled" in withDatabase {
      for {
        f      <- fixture(state = None, allowInit = true)
        result <- f.authorize()
        counts <- f.counts
      } yield {
        result must beNone
        counts shouldEqual ((0, 0, 0))
      }
    }
    "deny no-history JIT when initialization is disabled" in withDatabase {
      for {
        f      <- fixture(state = None)
        result <- f.authorize()
        counts <- f.counts
      } yield { result shouldEqual None; counts shouldEqual ((0, 0, 0)) }
    }
    "never authenticate or link a nonmember password account through an email match" in withDatabase {
      for {
        f      <- fixture(state = None, allowInit = true, existing = Some(IdentityType.MailAddress))
        result <- f.authorize()
        counts <- f.counts
      } yield { result shouldEqual None; counts shouldEqual ((1, 0, 0)) }
    }
    "deny active legacy SAML membership login based only on an email match" in withDatabase {
      for {
        f      <- fixture(state = None, existing = Some(IdentityType.Saml), activeMember = true)
        result <- f.authorize()
        counts <- f.counts
      } yield {
        result must beNone
        counts shouldEqual ((1, 1, 0))
      }
    }
    "preserve ordinary bound member login and existing roles despite a pending duplicate invitation" in withDatabase {
      for {
        f      <- fixture(existing = Some(IdentityType.Saml), activeMember = true, bound = true)
        result <- f.authorize()
        member <- memberships.find(OrganizationUserFilters(organizationId = Some(f.organization.id), userId = f.user.map(_.id)))
      } yield {
        result.flatMap(_.purpose) shouldEqual Some("Login")
        result.flatMap(_.accountAuthentication) must beSome
        member.get.roles shouldEqual Seq(OrganizationUserRole.Manager)
      }
    }
    "retain current bound account evidence server-side for explicit invitation acceptance" in withDatabase {
      for {
        f      <- fixture(existing = Some(IdentityType.Saml), bound = true)
        result <- f.authorize()
        counts <- f.counts
      } yield {
        result.flatMap(_.purpose) shouldEqual Some("Invitation")
        result.flatMap(_.userId) shouldEqual f.user.map(_.id)
        result.flatMap(_.accountAuthentication).map(_.authenticatedAt) shouldEqual Some(now)
        counts shouldEqual ((1, 0, 1))
      }
    }
    "block legacy v1 history instead of falling through to Employee JIT" in withDatabase {
      for {
        f <- fixture(allowInit = true)
        _ <- sqlu"UPDATE invitation SET protocol_version=1,first_name=NULL,last_name=NULL WHERE id=${f.invitation.get.invitation.id.toString}::uuid"
        result <- f.authorize()
        counts <- f.counts
      } yield { result shouldEqual None; counts shouldEqual ((0, 0, 0)) }
    }
    "reject a known subject with changed email without creating a duplicate account" in withDatabase {
      for {
        f <- fixture(state = None, allowInit = true, existing = Some(IdentityType.Saml), bound = true)
        result <- service().authorizeAction(
                    f.request,
                    f.config,
                    f.context.copy(attributes = f.context.attributes.updated("email", List("changed@example.test")))
                  )
        counts  <- f.counts
        changed <- emails.resolve("changed@example.test")
      } yield { result shouldEqual None; counts shouldEqual ((1, 0, 1)); changed shouldEqual EmailIdentityRepository.Resolution.Empty }
    }
    "reject binding-user and canonical-email owner disagreement without relinking either account" in withDatabase {
      for {
        f      <- fixture(state = None, allowInit = true, existing = Some(IdentityType.Saml), bound = true)
        other  <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
        _      <- sqlu"UPDATE user_identity SET user_id=${other.id.toString}::uuid WHERE user_id=${f.user.get.id.toString}::uuid"
        result <- f.authorize()
        stored <- bindings.find(SamlBindingKey(f.organization.id, 1L, f.context.issuer, f.context.subject))
        counts <- f.counts
      } yield { result shouldEqual None; stored.map(_.userId) shouldEqual f.user.map(_.id); counts shouldEqual ((1, 0, 1)) }
    }
    "give an existing unbound account only a limited invitation context" in withDatabase {
      for {
        f      <- fixture(existing = Some(IdentityType.MailAddress))
        result <- f.authorize()
        counts <- f.counts
      } yield {
        result.flatMap(_.purpose) shouldEqual Some("Invitation"); result.flatMap(_.userId) shouldEqual None;
        result.flatMap(_.accountAuthentication) shouldEqual None; counts shouldEqual ((1, 0, 0))
      }
    }
    "fail closed for a pending invitation when invitation components are disabled" in withDatabase {
      for {
        f      <- fixture(allowInit = true)
        result <- f.authorize(enabled = false)
        counts <- f.counts
      } yield { result shouldEqual None; counts shouldEqual ((0, 0, 0)) }
    }
  }

  private def run[A](action: DBIO[A]): A =
    scala.concurrent.Await.result(db.run(action.transactionally), scala.concurrent.duration.Duration(30, "seconds"))

  private def withCommitted[A](pendingInvite: Boolean)(test: Fixture => A): A = {
    val f = run(fixture(state = Option.when(pendingInvite)("Pending"), allowInit = true))
    try test(f)
    finally
      run(for {
        owner <- emails.resolve(f.context.email)
        ids = Vector(f.actor) ++ f.user.map(_.id) ++ (owner match {
                case EmailIdentityRepository.Resolution.Single(user, _) => Vector(user.id)
                case _                                                  => Vector.empty
              })
        _ <- sqlu"DELETE FROM organization_user WHERE organization_id=${f.organization.id.toString}::uuid"
        _ <-
          sqlu"UPDATE invitation_access SET saml_request_id=NULL WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${f.organization.id.toString}::uuid)"
        _ <- sqlu"DELETE FROM saml_request WHERE organization_id=${f.organization.id.toString}::uuid"
        _ <- sqlu"DELETE FROM saml_identity_binding WHERE organization_id=${f.organization.id.toString}::uuid"
        _ <- sqlu"DELETE FROM saml_config WHERE organization_id=${f.organization.id.toString}::uuid"
        _ <-
          sqlu"DELETE FROM invitation_access WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${f.organization.id.toString}::uuid)"
        _ <- sqlu"DELETE FROM invitation WHERE organization_id=${f.organization.id.toString}::uuid"
        _ <- sqlu"DELETE FROM organization WHERE id=${f.organization.id.toString}::uuid"
        _ <- DBIO.sequence(
               ids.distinct.map(id =>
                 DBIO.seq(
                   sqlu"DELETE FROM user_password WHERE user_identity_id IN (SELECT id FROM user_identity WHERE user_id=${id.toString}::uuid)",
                   sqlu"DELETE FROM user_identity WHERE user_id=${id.toString}::uuid",
                   sqlu"""DELETE FROM "user" WHERE id=${id.toString}::uuid"""
                 )
               )
             )
      } yield ())
  }

  "SAML transaction boundaries" should {
    "allow exactly one callback exchange across two real connections" in withCommitted(pendingInvite = true) { f =>
      import java.util.concurrent.{CountDownLatch, TimeUnit}
      import scala.concurrent.Await
      import scala.concurrent.duration._
      val issued     = run(f.authorize()).get
      val consuming  = new CountDownLatch(1)
      val release    = new CountDownLatch(1)
      val readByBoth = new CountDownLatch(2)
      val concurrentRequests = new SamlRequestRepositoryImpl {
        override def find(token: SamlOneTimeToken): DBIO[Option[SamlRequest]] =
          super.find(token).map { value => readByBoth.countDown(); value }(SamlInvitationServiceSpec.this.ec)
        override def consume(id: SamlRequestId, token: SamlOneTimeToken): DBIO[Boolean] =
          SimpleDBIO { _ => consuming.countDown(); require(release.await(20, TimeUnit.SECONDS), "release callback consume") }
            .flatMap(_ => super.consume(id, token))(SamlInvitationServiceSpec.this.ec)
      }
      val parallelDb = Database.forURL(
        sys.env("TEST_DB_URL"),
        sys.env.getOrElse("TEST_DB_USER", "postgres"),
        sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
        driver = "org.postgresql.Driver",
        executor = AsyncExecutor("ct208-saml-callback", 2, 2, 20, maxConnections = 2)
      )
      val actual = service(requestRepository = concurrentRequests)
      try {
        val first = parallelDb.run(actual.exchangeAction(issued.token.get, f.organization.slug, Some("synthetic-browser-nonce")).transactionally)
        require(consuming.await(20, TimeUnit.SECONDS), "first callback must reach locked consume")
        val second = parallelDb.run(actual.exchangeAction(issued.token.get, f.organization.slug, Some("synthetic-browser-nonce")).transactionally)
        require(readByBoth.await(20, TimeUnit.SECONDS), "both independent transactions must read the unused code")
        release.countDown()
        val results = Vector(Await.result(first, 30.seconds), Await.result(second, 30.seconds))
        results.count(_.isInstanceOf[Continuation]) mustEqual 1
        results.count(_ == Denied) mustEqual 1
        run(requests.find(f.request.id)).get.used must beTrue
        run(f.counts) mustEqual (0, 0, 0)
      } finally { release.countDown(); parallelDb.close() }
    }
    "roll back ACS context if callback persistence fails" in withCommitted(pendingInvite = true) { f =>
      val failingRequests = new SamlRequestRepositoryImpl {
        override def update(request: SamlRequest): DBIO[Option[SamlRequest]] =
          if (request.token.isDefined) DBIO.successful(None) else super.update(request)
      }
      val result = scala.util.Try(run(service(requestRepository = failingRequests).authorizeAction(f.request, f.config, f.context)))
      result.isFailure must beTrue
      run(requests.find(f.request.id)) must beSome(f.request)
      run(f.counts) mustEqual (0, 0, 0)
      run(
        sql"SELECT count(*) FROM invitation_access WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${f.organization.id.toString}::uuid)"
          .as[Int]
          .head
      ) mustEqual 0
    }
    "keep unknown callback classification read-only" in withDatabase {
      service().classifyContinuationAction("missing-cookie").map(_ must beNone)
    }
  }

  private def continuation(result: SamlInvitationService.CallbackResult): Option[Continuation] = result match {
    case value: Continuation => Some(value)
    case _                   => None
  }
  "nonce-authenticated callback exchange" should {
    "issue only a limited context cookie and consume its callback code exactly once" in withDatabase {
      for {
        f      <- fixture()
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        replay <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        stored <- requests.find(f.request.id)
        counts <- f.counts
      } yield {
        continuation(result).map(_.grant.dto.nextAction) shouldEqual Some("VerifyLegacySso")
        continuation(result).map(_.organizationSlug) shouldEqual Some(f.organization.slug.toString)
        stored.map(_.used: Boolean) shouldEqual Some(true)
        replay shouldEqual Denied
        counts shouldEqual ((0, 0, 0))
      }
    }
    "return session state only after consuming a current bound member code" in withDatabase {
      for {
        f      <- fixture(state = None, existing = Some(IdentityType.Saml), activeMember = true, bound = true)
        issued <- f.authorize()
        result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
      } yield result must beLike { case Session(user, _, Some(proof), at, _) =>
        user.id shouldEqual issued.get.userId.get
        proof.authenticatedAt shouldEqual now
        at shouldEqual now
      }
    }
    "reject substituted nonce and callback organization without consuming the valid code" in withDatabase {
      for {
        f            <- fixture()
        issued       <- f.authorize()
        wrongNonce   <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("different-browser"))
        wrongOrg     <- service().exchangeAction(issued.get.token.get, OrganizationSlug("other-organization"), Some("synthetic-browser-nonce"))
        missingNonce <- service().exchangeAction(issued.get.token.get, f.organization.slug, None)
        unused       <- requests.find(f.request.id)
        valid        <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
      } yield {
        Vector(wrongNonce, wrongOrg, missingNonce) shouldEqual Vector.fill(3)(Denied)
        unused.map(_.used: Boolean) shouldEqual Some(false)
        continuation(valid) must beSome
      }
    }
    Seq(false, true).foreach { ordinary =>
      s"exchange a ${if (ordinary) "bound login" else "continuation"} with an advancing nanosecond clock after PostgreSQL reload" in withDatabase {
        val advancing = new Clock {
          private val ticks                                    = new java.util.concurrent.atomic.AtomicLong(123456789L)
          override def getZone: java.time.ZoneId               = ZoneOffset.UTC
          override def withZone(zone: java.time.ZoneId): Clock = this
          override def instant(): Instant                      = now.plusNanos(ticks.getAndAdd(1234567L))
        }
        val actual = service(testClock = advancing)
        for {
          f        <- fixture(existing = Option.when(ordinary)(IdentityType.Saml), activeMember = ordinary, bound = ordinary)
          issued   <- actual.authorizeAction(f.request, f.config, f.context)
          reloaded <- requests.find(f.request.id)
          result   <- actual.exchangeAction(reloaded.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        } yield {
          issued must beSome
          if (ordinary) result must beAnInstanceOf[Session]
          else continuation(result) must beSome
        }
      }
    }
    "preserve linked context identity email proof verified user and original TTL while rotating its cookie" in withDatabase {
      val oldCookie = secrets.generate()
      val oldCsrf   = secrets.generate()
      for {
        f <- fixture(existing = Some(IdentityType.Saml), bound = true)
        original = Access(
                     InvitationAccessId(UUID.randomUUID()),
                     Digest(secrets.hash(oldCookie)),
                     Digest(secrets.hash(oldCsrf)),
                     f.invitation.get.invitation.id,
                     1,
                     Proof(Some(now.minusSeconds(30)), None),
                     f.user.map(_.id),
                     Some(f.request.id),
                     now.plusSeconds(80),
                     None
                   )
        _       <- accesses.insert(original)
        linked  <- requests.update(f.request.copy(purpose = Some("Invitation"), invitationAccessId = Some(original.id)))
        issued  <- service().authorizeAction(linked.get, f.config, f.context)
        result  <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        current <- accesses.findById(original.id)
        old     <- accesses.findByCookieHash(Digest(secrets.hash(oldCookie)))
      } yield {
        continuation(result).map(_.grant.dto.nextAction) shouldEqual Some("Accept")
        current.map(_.id) shouldEqual Some(original.id)
        current.map(_.proof.emailVerifiedAt) shouldEqual Some(original.proof.emailVerifiedAt)
        current.map(_.verifiedUserId) shouldEqual Some(original.verifiedUserId)
        current.map(_.samlRequestId) shouldEqual Some(original.samlRequestId)
        current.map(_.expiresAt) shouldEqual Some(original.expiresAt)
        current.flatMap(_.proof.saml).map(_.subject) shouldEqual Some(f.context.subject)
        old shouldEqual None
        current.map(_.csrfHash == original.csrfHash) shouldEqual Some(false)
      }
    }
    Seq("Expired", "Completed", "Generation", "Cancelled", "Epoch", "Disabled", "Subject").foreach { changed =>
      s"reject $changed state after ACS without consuming the callback or reopening the context" in withDatabase {
        for {
          f       <- fixture()
          issued  <- f.authorize()
          current <- accesses.findById(issued.get.invitationAccessId.get)
          _ <- changed match {
                 case "Expired"   => accesses.revoke(current.get.id, now)
                 case "Completed" => accesses.update(current.get.copy(completedAt = Some(now)), 1).map(_ => ())
                 case "Generation" =>
                   sqlu"UPDATE invitation SET generation=generation+1 WHERE id=${f.invitation.get.invitation.id.toString}::uuid".map(_ => ())
                 case "Cancelled" =>
                   sqlu"UPDATE invitation SET archived_at=${now.toString}::timestamptz WHERE id=${f.invitation.get.invitation.id.toString}::uuid".map(
                     _ => ()
                   )
                 case "Epoch"    => configs.upsert(f.config.copy(idpCert = SamlIdpCert("rotated-certificate"))).map(_ => ())
                 case "Disabled" => configs.upsert(f.config.copy(isEnabled = SamlIsEnabled(false))).map(_ => ())
                 case _          => requests.update(issued.get.copy(verifiedSubject = Some("substituted-subject"))).map(_ => ())
               }
          result <- service().exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
          stored <- requests.find(f.request.id)
        } yield { result shouldEqual Denied; stored.map(_.used: Boolean) shouldEqual Some(false) }
      }
    }
    "deny invitation callback completion when its components are disabled" in withDatabase {
      for {
        f      <- fixture()
        issued <- f.authorize()
        result <- service(enabled = false).exchangeAction(issued.get.token.get, f.organization.slug, Some("synthetic-browser-nonce"))
        stored <- requests.find(f.request.id)
      } yield { result shouldEqual Denied; stored.map(_.used: Boolean) shouldEqual Some(false) }
    }
  }
}
