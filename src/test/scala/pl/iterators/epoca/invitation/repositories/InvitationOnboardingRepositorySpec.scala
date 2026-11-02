package pl.iterators.epoca.invitation.repositories

import io.circe.Json
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityValue}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber

import java.time.Instant
import java.util.UUID

class InvitationOnboardingRepositorySpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val repo       = new InvitationOnboardingRepositoryImpl
  private val accessRepo = new InvitationAccessRepositoryImpl
  private val now        = Instant.parse("2026-10-01T12:00:00Z")
  private val hash       = Digest(Array.fill[Byte](32)(1))

  "onboarding persistence" should {
    "preserve current data after stale writes and reject obsolete delivery results" in withDatabase {
      for {
        actor <- withUser()
        org   <- withOrganization()
        base = allGenerators[Invitation].normal.generate.copy(
                 organizationId = org.id,
                 createdBy = actor.id,
                 modifiedBy = actor.id,
                 identityType = IdentityType.MailAddress,
                 identityValue = UserIdentityValue("Anna@example.test"),
                 roles = Seq(OrganizationUserRole.Employee),
                 createdAt = InvitationCreatedAt(now),
                 modifiedAt = InvitationModifiedAt(now),
                 expireAt = Some(InvitationExpireAt(now.plusSeconds(86400))),
                 acceptAt = None,
                 rejectAt = None,
                 archivedAt = None
               )
        record = Record(base, 2, Some("Anna"), Some("Test"), 0, 1, Some(hash), None, Some("en"), None)
        _       <- repo.insert(record)
        locked  <- repo.lockInvitation(base.id)
        changed <- repo.update(record.copy(firstName = Some("Anne"), generation = 2), 0, 1)
        stale   <- repo.update(record.copy(firstName = Some("Stale")), 0, 1)
        current <- repo.find(base.id)
        attempt = DeliveryAttempt(InvitationDeliveryAttemptId(UUID.randomUUID()), base.id, 1, now, None, None, None)
        _        <- repo.insertAttempt(attempt)
        obsolete <- repo.finishAttempt(attempt.id, base.id, 1, now.plusSeconds(1), DeliveryResult.Succeeded, None)
      } yield {
        locked.map(_.revision) shouldEqual Some(0L)
        changed should beTrue
        stale should beFalse
        current.flatMap(_.firstName) shouldEqual Some("Anne")
        current.map(_.revision) shouldEqual Some(1L)
        obsolete should beFalse
      }
    }
    "use JVM trim semantics for generated keys, pending/history filters, upgrade and recipient equality" in withDatabase {
      val padding = (1 to 32).map(_.toChar).mkString
      for {
        actor <- withUser()
        org   <- withOrganization()
        base = allGenerators[Invitation].normal.generate.copy(
                 organizationId = org.id,
                 createdBy = actor.id,
                 modifiedBy = actor.id,
                 identityType = IdentityType.MailAddress,
                 identityValue = UserIdentityValue(padding + "Anna@example.test" + padding),
                 roles = Seq(OrganizationUserRole.Employee),
                 createdAt = InvitationCreatedAt(now),
                 modifiedAt = InvitationModifiedAt(now),
                 expireAt = Some(InvitationExpireAt(now.plusSeconds(86400))),
                 acceptAt = None,
                 rejectAt = None,
                 archivedAt = None
               )
        legacy = Record(base, 1, None, None, 0, 1, None, None, Some("en"), None)
        _             <- repo.insert(legacy)
        read          <- repo.find(base.id)
        pending       <- repo.findPendingByEmail(org.id, "anna@example.test")
        paddedPending <- repo.findPendingByEmail(org.id, padding + "ANNA@example.test" + padding)
        page          <- repo.page(org.id, PageSizeAndNumber(10, 1), Some("anna@example.test"))
        paddedPage    <- repo.page(org.id, PageSizeAndNumber(10, 1), Some(padding + "ANNA@example.test" + padding))
        count         <- repo.count(org.id, Some("anna@example.test"))
        paddedCount   <- repo.count(org.id, Some(padding + "ANNA@example.test" + padding))
        v2 = legacy.copy(protocolVersion = 2, firstName = Some("Anna"), lastName = Some("Test"), tokenHash = Some(hash))
        upgraded         <- repo.update(v2.copy(invitation = base.copy(identityValue = UserIdentityValue("anna@example.test"))), 0, 1)
        upgradedRead     <- repo.find(base.id)
        sameRecipient    <- repo.update(v2.copy(firstName = Some("Anne")), 1, 1)
        changedRecipient <- repo.update(v2.copy(invitation = base.copy(identityValue = UserIdentityValue("other@example.test"))), 2, 1)
      } yield {
        assert(read.flatMap(_.canonicalEmail).contains("anna@example.test"))
        assert(pending.map(_.invitation.id).contains(base.id))
        assert(paddedPending == pending)
        assert(page.map(_.invitation.id) == Vector(base.id))
        assert(paddedPage == page)
        assert(count == 1L && paddedCount == count)
        assert(upgraded)
        assert(upgradedRead.exists(_.invitation.identityValue == "anna@example.test"))
        assert(sameRecipient)
        assert(!changedRecipient)
        ok
      }
    }
    "page legacy and v2 invitations stably and persist replay, audit and browser proof" in withDatabase {
      for {
        actor <- withUser()
        org   <- withOrganization()
        base = allGenerators[Invitation].normal.generate.copy(
                 organizationId = org.id,
                 createdBy = actor.id,
                 modifiedBy = actor.id,
                 identityType = IdentityType.MailAddress,
                 identityValue = UserIdentityValue("one@example.test"),
                 roles = Seq(OrganizationUserRole.Employee),
                 createdAt = InvitationCreatedAt(now),
                 modifiedAt = InvitationModifiedAt(now),
                 expireAt = Some(InvitationExpireAt(now.plusSeconds(86400))),
                 acceptAt = None,
                 rejectAt = None,
                 archivedAt = None
               )
        record = Record(base, 2, Some("Anna"), Some("Test"), 0, 1, Some(hash), None, Some("en"), None)
        _ <- repo.insert(record)
        legacy = record.copy(
                   invitation = base.copy(id = InvitationId(UUID.randomUUID()), identityValue = UserIdentityValue("two@example.test")),
                   protocolVersion = 1,
                   firstName = None,
                   lastName = None,
                   tokenHash = None
                 )
        _     <- repo.insert(legacy)
        page1 <- repo.page(org.id, PageSizeAndNumber(1, 1))
        page2 <- repo.page(org.id, PageSizeAndNumber(1, 2))
        count <- repo.count(org.id)
        upgraded <-
          repo.update(
            legacy.copy(protocolVersion = 2, firstName = Some("Two"), lastName = Some("Test"), tokenHash = Some(Digest(Array.fill[Byte](32)(8)))),
            0,
            1
          )
        upgradeRead   <- repo.find(legacy.invitation.id)
        downgraded    <- repo.update(legacy, 1, 1)
        changedEmail  <- repo.update(record.copy(invitation = base.copy(identityValue = UserIdentityValue("other@example.test"))), 0, 1)
        tokenRecord   <- repo.findByTokenHash(hash)
        pendingRecord <- repo.findPendingByEmail(org.id, "one@example.test")
        emailHistory  <- repo.page(org.id, PageSizeAndNumber(10, 1), Some("one@example.test"))
        emailCount    <- repo.count(org.id, Some("one@example.test"))
        attempt = DeliveryAttempt(InvitationDeliveryAttemptId(UUID.randomUUID()), base.id, 1, now, None, None, None)
        _        <- repo.insertAttempt(attempt)
        finished <- repo.finishAttempt(attempt.id, base.id, 1, now.plusSeconds(1), DeliveryResult.Succeeded, None)
        attempts <- repo.attempts(base.id)
        audit = Audit(InvitationAuditId(UUID.randomUUID()), base.id, Some(actor.id), "Created", now, None, Some(Json.obj()))
        _       <- repo.appendAudit(audit)
        history <- repo.audit(base.id)
        key     = CommandKey(actor.id, org.id, "Create", UUID.randomUUID())
        command = Command(key, hash, base.id, Json.obj("id" -> Json.fromString(base.id.toString)))
        saved    <- repo.insertCommand(command)
        repeated <- repo.insertCommand(command)
        replay   <- repo.findCommand(key)
        access = Access(
                   InvitationAccessId(UUID.randomUUID()),
                   hash,
                   Digest(Array.fill[Byte](32)(2)),
                   base.id,
                   1,
                   Proof(Some(now), Some(VerifiedSamlProof(org.id, 1, "issuer", "subject", "anna@example.test", now))),
                   None,
                   None,
                   now.plusSeconds(600),
                   None
                 )
        _                  <- accessRepo.insert(access)
        found              <- accessRepo.lockByCookieHash(hash)
        generationMismatch <- accessRepo.update(access.copy(generation = 2), 1)
        updated            <- accessRepo.update(access.copy(verifiedUserId = Some(actor.id), completedAt = Some(now)), 1)
        completed          <- accessRepo.findByCookieHash(hash)
        cannotReopen       <- accessRepo.update(access, 1)
        missingCookie      <- accessRepo.findByCookieHash(Digest(Array.fill[Byte](32)(7)))
        staleAttempt       <- repo.finishAttempt(attempt.id, base.id, 1, now.plusSeconds(2), DeliveryResult.Failed, Some("MAIL_FAILED"))
        missingReplay      <- repo.findCommand(key.copy(requestId = UUID.randomUUID()))
        consumed1          <- accessRepo.consumeRateBucket("email", hash, now, now.plusSeconds(60), 1)
        consumed2          <- accessRepo.consumeRateBucket("email", hash, now, now.plusSeconds(60), 1)
      } yield {
        (page1 ++ page2).map(_.invitation.id).distinct.size shouldEqual 2
        count shouldEqual 2L
        tokenRecord.map(_.invitation.id) shouldEqual Some(base.id)
        pendingRecord.map(_.invitation.id) shouldEqual Some(base.id)
        emailHistory.map(_.invitation.id) shouldEqual Vector(base.id)
        emailCount shouldEqual 1L
        upgraded should beTrue
        upgradeRead.map(_.protocolVersion) shouldEqual Some(2)
        downgraded should beFalse
        changedEmail should beFalse
        finished should beTrue
        attempts.head.result shouldEqual Some(DeliveryResult.Succeeded)
        history shouldEqual Vector(audit)
        saved should beTrue
        repeated should beFalse
        replay shouldEqual Some(command)
        found shouldEqual Some(access)
        generationMismatch should beFalse
        updated should beTrue
        completed.flatMap(_.completedAt) shouldEqual Some(now)
        consumed1 should beTrue
        consumed2 should beFalse
        cannotReopen should beFalse
        missingCookie should beNone
        staleAttempt should beFalse
        missingReplay should beNone
        access.toString must not contain "anna@example.test"
        hash.toString shouldEqual "Digest(<redacted>)"
      }
    }
  }
}
