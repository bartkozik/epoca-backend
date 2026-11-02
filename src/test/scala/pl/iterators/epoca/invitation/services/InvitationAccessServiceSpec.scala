package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories._
import pl.iterators.epoca.invitation.services.InvitationAccessService._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationDomain}
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.user.repositories.{EmailIdentityRepositoryImpl, UserIdentityRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

class InvitationAccessServiceSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val repo     = new InvitationOnboardingRepositoryImpl
  private val accesses = new InvitationAccessRepositoryImpl
  private val secrets  = new InvitationSecretService
  private val now      = Instant.parse("2026-10-02T10:00:00Z")
  private val browser  = new InvitationBrowserSecurity(Set("https://app.example.test"), "/api", false, false, secrets)
  private def service(at: Instant = now) =
    new InvitationAccessService(null, repo, accesses, new EmailIdentityRepositoryImpl, secrets, browser, null, Clock.fixed(at, ZoneOffset.UTC))
  private def fixture(ttl: Long = 86400, legacy: Boolean = false): DBIO[(Record, String)] = for {
    actor <- withUser()
    org   <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
    token = secrets.generate()
    invitation = allGenerators[Invitation].normal.generate.copy(
                   organizationId = org.id,
                   createdBy = actor.id,
                   modifiedBy = actor.id,
                   identityType = IdentityType.MailAddress,
                   identityValue = UserIdentityValue(s"recipient-${UUID.randomUUID()}@example.test"),
                   roles = Seq(OrganizationDomain.OrganizationUserRole.Employee),
                   expireAt = Some(InvitationExpireAt(now.plusSeconds(ttl))),
                   acceptAt = None,
                   rejectAt = None,
                   archivedAt = None
                 )
    record =
      Record(
        invitation,
        if (legacy) 1 else 2,
        Some("Anna"),
        Some("Test"),
        0,
        1,
        if (legacy) None else Some(Digest(secrets.hash(token))),
        None,
        Some("en"),
        None
      )
    _    <- repo.insert(record)
    read <- repo.find(invitation.id)
  } yield (read.get, token)
  private def granted(result: Result): Granted = result.asInstanceOf[Granted]
  private def enableSaml(record: Record): DBIO[Unit] = {
    import pl.iterators.epoca.saml.domain.{SamlConfig, SamlDomain}
    new pl.iterators.epoca.saml.repositories.SamlConfigRepositoryImpl()
      .upsert(
        allGenerators[SamlConfig].normal.generate.copy(
          organizationId = record.invitation.organizationId,
          isEnabled = SamlDomain.SamlIsEnabled(true),
          idpEntityId = SamlDomain.SamlIdpEntityId("issuer")
        )
      )
      .map(_ => ())
  }
  "stable CSRF security regression" should {
    "preserve the original POST token and stored context across repeated GET summaries" in withDatabase {
      for {
        f       <- fixture()
        grant   <- service().exchangeAction(f._2, None).map(granted)
        before  <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        first   <- service().summaryAction(grant.cookie).map(granted)
        second  <- service().summaryAction(grant.cookie).map(granted)
        after   <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        revoked <- service().revokeAction(grant.cookie, Some("https://app.example.test"), Some(grant.dto.csrfToken))
      } yield {
        first.dto.csrfToken mustEqual grant.dto.csrfToken
        second.dto.csrfToken mustEqual grant.dto.csrfToken
        after mustEqual before
        revoked mustEqual Revoked
      }
    }
  }

  "invitation access" should {
    Seq(false, true).foreach { bound =>
      s"preserve SSO-first linked evidence and original TTL during real email exchange (bound=$bound)" in withDatabase {
        import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepositoryImpl, UserPasswordRepositoryImpl}
        import pl.iterators.epoca.auth.services.AccountAuthenticationValidator
        import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
        import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlBindingKey, SamlIdentityBinding, SamlRequest}
        import pl.iterators.epoca.saml.domain.SamlDomain._
        import pl.iterators.epoca.saml.repositories.{SamlConfigRepositoryImpl, SamlIdentityBindingRepositoryImpl, SamlRequestRepositoryImpl}
        import pl.iterators.epoca.saml.services.SamlInvitationService
        import pl.iterators.epoca.user.repositories.UserRepositoryImpl
        val users       = new UserRepositoryImpl
        val identities  = new UserIdentityRepositoryImpl
        val emails      = new EmailIdentityRepositoryImpl
        val passwords   = new UserPasswordRepositoryImpl
        val requests    = new SamlRequestRepositoryImpl
        val configs     = new SamlConfigRepositoryImpl
        val bindings    = new SamlIdentityBindingRepositoryImpl
        var currentTime = now
        val clock = new Clock {
          override def getZone: java.time.ZoneId               = ZoneOffset.UTC
          override def withZone(zone: java.time.ZoneId): Clock = this
          override def instant(): Instant                      = currentTime
        }
        val saml = new SamlInvitationService(
          requests,
          configs,
          bindings,
          repo,
          accesses,
          emails,
          new OrganizationRepositoryImpl,
          users,
          identities,
          new OrganizationUserRepositoryImpl,
          new AccountAuthenticationValidator(new AccountAuthenticationRepositoryImpl, identities, passwords),
          secrets,
          clock,
          Some(service())
        )
        for {
          f    <- fixture()
          user <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
          _ <- identities.insert(
                 allGenerators[UserIdentity].normal.generate
                   .copy(userId = user.id, `type` = IdentityType.Saml, value = f._1.invitation.identityValue, confirmed = UserIdentityConfirmed(true))
               )
          _              <- enableSaml(f._1)
          originalConfig <- configs.find(f._1.invitation.organizationId)
          config         <- configs.upsert(originalConfig.get.copy(validFor = SamlValidityDuration(java.time.Duration.ofMinutes(5))))
          epoch          <- configs.trustEpoch(config.organizationId)
          _ <- if (bound)
                 bindings.insert(
                   SamlIdentityBinding(
                     SamlBindingKey(config.organizationId, epoch.get, config.idpEntityId, "subject"),
                     user.id,
                     f._1.canonicalEmail.get,
                     now.minusSeconds(30),
                     io.circe.Json.obj()
                   )
                 )
               else DBIO.successful(())
          request <- requests.insert(
                       SamlRequest(
                         SamlRequestId("_" + UUID.randomUUID()),
                         None,
                         None,
                         SamlRequestUsed(false),
                         SamlRequestCreatedAt(now),
                         organizationId = Some(config.organizationId),
                         trustEpoch = epoch,
                         purpose = Some("Login"),
                         browserNonceHash = Some(secrets.hash("nonce").toVector)
                       )
                     )
          issued <-
            saml.authorizeAction(request, config, SamlAuthContext("subject", Map("email" -> List(f._1.canonicalEmail.get)), config.idpEntityId))
          org <- new OrganizationRepositoryImpl().find(
                   pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters(id = Some(config.organizationId))
                 )
          exchanged <- saml.exchangeAction(issued.get.token.get, org.get.slug, Some("nonce"))
          grant = exchanged.asInstanceOf[SamlInvitationService.Continuation].grant
          before   <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
          enriched <- service(now.plusSeconds(60)).exchangeAction(f._2, Some(grant.cookie))
          after    <- accesses.findByCookieHash(Digest(secrets.hash(granted(enriched).cookie)))
          _ = currentTime = now.plusSeconds(60)
          next <- saml.classifyContinuationAction(granted(enriched).cookie)
        } yield {
          granted(enriched).cookie mustEqual grant.cookie
          after.get.id mustEqual before.get.id
          after.get.proof.saml mustEqual before.get.proof.saml
          after.get.proof.emailVerifiedAt must beSome(now.plusSeconds(60))
          after.get.samlRequestId mustEqual before.get.samlRequestId
          after.get.verifiedUserId mustEqual before.get.verifiedUserId
          after.get.verifiedUserId mustEqual Option.when(bound)(user.id)
          after.get.expiresAt mustEqual before.get.expiresAt
          after.get.csrfHash mustEqual before.get.csrfHash
          next must beSome(if (bound) "Accept" else "SignIn")
        }
      }
    }

    "redact server-only SAML evidence in diagnostic strings" in {
      val proof = VerifiedSamlProof(OrganizationDomain.OrganizationId(UUID.randomUUID()), 1, "issuer", "private-subject", "private@example.test", now)
      proof.toString must not contain "private-subject"
      Proof(Some(now), Some(proof)).toString must not contain "private@example.test"
    }
    "fail closed on ambiguous recipient ownership without leaking a collision reason" in withDatabase {
      for {
        f      <- fixture()
        first  <- withUser()
        second <- withUser()
        _ <- new UserIdentityRepositoryImpl().insert(
               allGenerators[UserIdentity].normal.generate
                 .copy(userId = first.id, `type` = IdentityType.MailAddress, value = f._1.invitation.identityValue)
             )
        _ <- new UserIdentityRepositoryImpl().insert(
               allGenerators[UserIdentity].normal.generate
                 .copy(userId = second.id, `type` = IdentityType.Saml, value = UserIdentityValue(" " + f._1.invitation.identityValue))
             )
        result <- service().exchangeAction(f._2, None)
      } yield result mustEqual InvalidInvitation
    }
    Seq("expired", "generation").foreach { state =>
      s"discard stale $state SAML context evidence on a new exchange" in withDatabase {
        for {
          f       <- fixture()
          initial <- service().exchangeAction(f._2, None)
          grant = granted(initial)
          row <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
          proof = VerifiedSamlProof(f._1.invitation.organizationId, 1, "issuer", "subject", f._1.canonicalEmail.get, now)
          _       <- accesses.update(row.get.copy(proof = Proof(None, Some(proof))), 1)
          _       <- if (state == "generation") repo.update(f._1.copy(generation = 2), 0, 1) else DBIO.successful(true)
          result  <- service(if (state == "expired") now.plusSeconds(900) else now).exchangeAction(f._2, Some(grant.cookie))
          current <- accesses.findByCookieHash(Digest(secrets.hash(granted(result).cookie)))
        } yield {
          current.get.id must not equalTo (row.get.id)
          current.get.proof.saml must beNone
        }
      }
    }

    "offer password account creation to a new recipient even when SSO is enabled" in withDatabase {
      for {
        f      <- fixture()
        _      <- enableSaml(f._1)
        result <- service().exchangeAction(f._2, None)
      } yield {
        granted(result).dto.nextAction mustEqual "CreateAccount"
        granted(result).dto.ssoEnabled must beTrue
      }
    }
    Seq("legacy", "generation", "organization", "email", "epoch", "issuer", "future", "old").foreach { mismatch =>
      s"reject trusted SAML output with mismatched $mismatch" in withDatabase {
        for {
          f <- fixture(legacy = mismatch == "legacy")
          _ <- enableSaml(f._1)
          valid = VerifiedSamlProof(f._1.invitation.organizationId, 1, "issuer", "subject", f._1.canonicalEmail.get, now)
          proof = mismatch match {
                    case "organization" => valid.copy(organizationId = OrganizationDomain.OrganizationId(UUID.randomUUID()))
                    case "email"        => valid.copy(canonicalEmail = "other@example.test")
                    case "epoch"        => valid.copy(trustEpoch = 2)
                    case "issuer"       => valid.copy(issuer = "other")
                    case "future"       => valid.copy(verifiedAt = now.plusSeconds(1))
                    case "old"          => valid.copy(verifiedAt = now.minusSeconds(900))
                    case _              => valid
                  }
          result <- service().fromVerifiedSamlAction(f._1.invitation.id, if (mismatch == "generation") 2 else 1, proof, None)
        } yield result mustEqual InvalidInvitation
      }
    }
    "mint a bounded SAML context and preserve email evidence on the same invitation" in withDatabase {
      for {
        f <- fixture()
        _ <- enableSaml(f._1)
        proof = VerifiedSamlProof(f._1.invitation.organizationId, 1, "issuer", "subject", f._1.canonicalEmail.get, now)
        saml    <- service().fromVerifiedSamlAction(f._1.invitation.id, 1, proof, None)
        samlRow <- accesses.findByCookieHash(Digest(secrets.hash(granted(saml).cookie)))
        email   <- service().exchangeAction(f._2, Some(granted(saml).cookie))
        both    <- service().fromVerifiedSamlAction(f._1.invitation.id, 1, proof, Some(granted(email).cookie))
        bothRow <- accesses.findByCookieHash(Digest(secrets.hash(granted(both).cookie)))
      } yield {
        samlRow.get.proof mustEqual Proof(None, Some(proof))
        bothRow.get.proof mustEqual Proof(Some(now), Some(proof))
        bothRow.get.verifiedUserId must beNone
        bothRow.get.expiresAt mustEqual now.plusSeconds(900)
      }
    }
    "allow a completed same-user context to read CSRF and revoke without reopening proofs" in withDatabase {
      for {
        f       <- fixture()
        user    <- withUser()
        initial <- service().exchangeAction(f._2, None)
        grant = granted(initial)
        row <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        completed = row.get.copy(verifiedUserId = Some(user.id), completedAt = Some(now))
        _ <- accesses.update(completed, 1)
        _ <- repo.update(f._1.copy(acceptedUserId = Some(user.id), invitation = f._1.invitation.copy(acceptAt = Some(InvitationAcceptAt(now)))), 0, 1)
        lockedUsers <- service().withContext(grant.cookie, allowCompleted = true)(DBIO.successful(Set.empty[UserId]))(
                         (
                           _,
                           _,
                           recipient
                         ) => DBIO.successful(recipient.lockedUserIds)
                       )
        fresh    <- service().summaryAction(grant.cookie)
        rejected <- service().exchangeAction(f._2, None)
        after    <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        revoked  <- service().revokeAction(grant.cookie, Some("https://app.example.test"), Some(granted(fresh).dto.csrfToken))
        finalRow <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
      } yield {
        rejected mustEqual InvalidInvitation
        lockedUsers must contain(user.id)
        after.get.copy(csrfHash = completed.csrfHash) mustEqual completed
        revoked mustEqual Revoked
        finalRow.get.completedAt must beSome(now)
        finalRow.get.expiresAt mustEqual now
      }
    }

    "exchange a valid v2 secret into independent opaque digested secrets and only email proof" in withDatabase {
      for {
        f      <- fixture()
        result <- service().exchangeAction(f._2, None)
        grant = granted(result)
        access <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
      } yield {
        grant.cookie.length mustEqual 43
        grant.dto.csrfToken.length mustEqual 43
        grant.cookie must not equalTo (grant.dto.csrfToken)
        access.get.csrfHash mustEqual Digest(secrets.hash(grant.dto.csrfToken))
        access.get.proof mustEqual Proof(Some(now), None)
        access.get.expiresAt mustEqual now.plusSeconds(900)
        access.get.verifiedUserId must beNone
        grant.dto.nextAction mustEqual "CreateAccount"
        grant.dto.productElementNames.toSet mustEqual Set(
          "organization",
          "email",
          "firstName",
          "lastName",
          "roles",
          "revision",
          "expiresAt",
          "csrfToken",
          "nextAction",
          "ssoEnabled"
        )
        grant.dto.organization.productElementNames.toSet mustEqual Set("name", "slug")
        grant.toString must not contain (grant.cookie)
        grant.dto.toString must not contain (grant.dto.csrfToken)
      }
    }
    "cap context lifetime at invitation expiry" in withDatabase {
      for {
        f      <- fixture(30)
        result <- service().exchangeAction(f._2, None)
      } yield granted(result).dto.expiresAt mustEqual now.plusSeconds(30)
    }
    Seq("bad", "accepted", "expired", "cancelled", "legacy", "archived organization").foreach { state =>
      s"return the same public error for $state invitations" in withDatabase {
        for {
          f <- fixture(legacy = state == "legacy")
          changed = state match {
                      case "accepted"  => f._1.copy(invitation = f._1.invitation.copy(acceptAt = Some(InvitationAcceptAt(now))))
                      case "expired"   => f._1.copy(invitation = f._1.invitation.copy(expireAt = Some(InvitationExpireAt(now))))
                      case "cancelled" => f._1.copy(invitation = f._1.invitation.copy(archivedAt = Some(InvitationArchivedAt(now))))
                      case "legacy"    => f._1.copy(protocolVersion = 1, tokenHash = None)
                      case _           => f._1
                    }
          _ <- repo.update(changed, 0, 1)
          _ <- if (state == "archived organization")
                 sqlu"UPDATE organization SET archived_at=now() WHERE id=${f._1.invitation.organizationId.toString}::uuid"
               else DBIO.successful(0)
          result <- service().exchangeAction(if (state == "bad") "bad" else f._2, None)
        } yield result mustEqual InvalidInvitation
      }
    }
    "preserve CSRF while refreshing current revision without activating an account or membership" in withDatabase {
      for {
        f       <- fixture()
        initial <- service().exchangeAction(f._2, None)
        grant = granted(initial)
        membersBefore <- sql"SELECT count(*) FROM organization_user".as[Long].head
        _             <- repo.update(f._1.copy(invitation = f._1.invitation.copy(roles = Seq(OrganizationDomain.OrganizationUserRole.Manager))), 0, 1)
        refreshed     <- service().summaryAction(grant.cookie)
        row           <- repo.find(f._1.invitation.id)
        membersAfter  <- sql"SELECT count(*) FROM organization_user".as[Long].head
        access        <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
      } yield {
        val dto = granted(refreshed).dto
        dto.revision mustEqual "1"
        dto.roles mustEqual Seq("Manager")
        dto.csrfToken mustEqual grant.dto.csrfToken
        secrets.matches(dto.csrfToken, access.get.csrfHash.toArray) must beTrue
        access.get.expiresAt mustEqual now.plusSeconds(900)
        row.get.invitation.acceptAt must beNone
        membersAfter mustEqual membersBefore
      }
    }
    Seq("generation", "expiry", "cancel", "archive").foreach { state =>
      s"recheck $state for existing contexts" in withDatabase {
        for {
          f       <- fixture()
          initial <- service().exchangeAction(f._2, None)
          grant = granted(initial)
          _ <- if (state == "generation") repo.update(f._1.copy(generation = 2), 0, 1)
               else if (state == "cancel")
                 repo.update(f._1.copy(invitation = f._1.invitation.copy(archivedAt = Some(InvitationArchivedAt(now)))), 0, 1)
               else DBIO.successful(true)
          _ <- if (state == "archive") sqlu"UPDATE organization SET archived_at=now() WHERE id=${f._1.invitation.organizationId.toString}::uuid"
               else DBIO.successful(0)
          result <- service(if (state == "expiry") now.plusSeconds(900) else now).summaryAction(grant.cookie)
        } yield result mustEqual InvalidInvitation
      }
    }
    "require current origin and CSRF before revoking and retain the expired row" in withDatabase {
      for {
        f       <- fixture()
        initial <- service().exchangeAction(f._2, None)
        grant = granted(initial)
        wrongOrigin <- service().revokeAction(grant.cookie, Some("https://evil.test"), Some(grant.dto.csrfToken))
        missing     <- service().revokeAction(grant.cookie, Some("https://app.example.test"), None)
        wrong       <- service().revokeAction(grant.cookie, Some("https://app.example.test"), Some("wrong"))
        valid       <- service().revokeAction(grant.cookie, Some("https://app.example.test"), Some(grant.dto.csrfToken))
        row         <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        expired     <- service().summaryAction(grant.cookie)
      } yield {
        wrongOrigin mustEqual Forbidden
        missing mustEqual Forbidden
        wrong mustEqual Forbidden
        valid mustEqual Revoked
        row must beSome
        row.get.expiresAt mustEqual now
        expired mustEqual InvalidInvitation
      }
    }
    "use SignIn for an existing unconfirmed account without inventing verified identity" in withDatabase {
      for {
        f    <- fixture()
        user <- withUser(allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
        _ <- new UserIdentityRepositoryImpl().insert(
               allGenerators[UserIdentity].normal.generate.copy(
                 userId = user.id,
                 `type` = IdentityType.MailAddress,
                 value = f._1.invitation.identityValue,
                 confirmed = UserIdentityConfirmed(false)
               )
             )
        result <- service().exchangeAction(f._2, None)
      } yield granted(result).dto.nextAction mustEqual "SignIn"
    }
    "preserve SAML only when exchanging for the same unexpired invitation generation" in withDatabase {
      for {
        f       <- fixture()
        other   <- fixture()
        initial <- service().exchangeAction(f._2, None)
        grant = granted(initial)
        row <- accesses.findByCookieHash(Digest(secrets.hash(grant.cookie)))
        proof = VerifiedSamlProof(f._1.invitation.organizationId, 1, "issuer", "subject", f._1.canonicalEmail.get, now)
        _            <- accesses.update(row.get.copy(proof = Proof(None, Some(proof))), 1)
        same         <- service().exchangeAction(f._2, Some(grant.cookie))
        sameRow      <- accesses.findByCookieHash(Digest(secrets.hash(granted(same).cookie)))
        different    <- service().exchangeAction(other._2, Some(grant.cookie))
        differentRow <- accesses.findByCookieHash(Digest(secrets.hash(granted(different).cookie)))
      } yield {
        sameRow.get.id mustEqual row.get.id
        sameRow.get.proof mustEqual Proof(Some(now), Some(proof))
        differentRow.get.proof mustEqual Proof(Some(now), None)
      }
    }
  }
}
