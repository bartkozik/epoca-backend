package pl.iterators.epoca.workunit.repositories

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyClaimResult.{Claimed, Existing}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyOperation.{Create, Update}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecord.{Completed, Processing, Tombstone}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecordValidationError.{
  InvalidOperation,
  InvalidOperationTarget,
  InvalidRecordShape,
  InvalidState
}
import pl.iterators.epoca.workunit.idempotency._
import pl.iterators.epoca.workunit.repositories.WorkUnitIdempotencyRepository.{
  WorkUnitIdempotencyCompletionInvariantViolation,
  WorkUnitIdempotencyRecordDecodingException
}
import slick.jdbc.SetParameter

import java.sql.Types
import java.time.Instant
import java.util.UUID
import scala.util.{Failure, Success, Try}

class WorkUnitIdempotencyRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private implicit val setUuid: SetParameter[UUID] = SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private val repository: WorkUnitIdempotencyRepository = new WorkUnitIdempotencyRepositoryImpl()

  private val canonicalRequest = Json.obj("command" -> Json.fromString("canonical"))
  private val outcomeBody      = Json.obj("workUnit" -> Json.fromString("stored"))
  private val version          = WorkUnitIdempotencyFingerprintVersion(1)
  private val createdAt        = Instant.parse("2026-07-27T10:00:00Z")

  private def scope(
    organizationId: OrganizationId,
    actorId: UserId,
    key: WorkUnitIdempotencyKey = WorkUnitIdempotencyKey(UUID.randomUUID())
  ): WorkUnitIdempotencyScope = WorkUnitIdempotencyScope(organizationId, actorId, key)

  private def claim(
    scope: WorkUnitIdempotencyScope,
    claimId: WorkUnitIdempotencyClaimId = WorkUnitIdempotencyClaimId(UUID.randomUUID()),
    canonical: Json = canonicalRequest,
    fingerprint: WorkUnitIdempotencyFingerprint = WorkUnitIdempotencyFingerprint("a" * 64),
    operation: WorkUnitIdempotencyOperation = Create,
    claimCreatedAt: Instant = createdAt
  ): NewWorkUnitIdempotencyClaim =
    NewWorkUnitIdempotencyClaim(claimId, scope, operation, version, canonical, fingerprint, claimCreatedAt)

  private def processing(value: NewWorkUnitIdempotencyClaim): Processing =
    Processing(value.claimId, value.scope, value.operation, value.fingerprintVersion, value.canonicalRequest, value.fingerprint, value.createdAt)

  private def failedWith[T <: Throwable: Manifest](attempt: Try[_]): T =
    attempt match {
      case Failure(error: T) => error
      case Failure(error)    => throw new AssertionError(s"Expected ${manifest[T].runtimeClass.getName}, got ${error.getClass.getName}", error)
      case Success(value)    => throw new AssertionError(s"Expected ${manifest[T].runtimeClass.getName}, got success: $value")
    }

  "claim" should {
    "atomically create the first Processing record" in withDatabase {
      for {
        organization <- withOrganization()
        actor        <- withUser()
        newClaim = claim(scope(organization.id, actor.id))
        result <- repository.claim(newClaim)
        stored <- repository.find(newClaim.scope)
      } yield {
        val expected = processing(newClaim)

        result shouldEqual Claimed(expected)
        stored shouldEqual Some(expected)
      }
    }

    "return the stored record when the same scope and key is claimed again" in withDatabase {
      for {
        organization <- withOrganization()
        actor        <- withUser()
        firstClaim  = claim(scope(organization.id, actor.id))
        secondClaim = claim(firstClaim.scope, canonical = Json.obj("different" -> Json.True), fingerprint = WorkUnitIdempotencyFingerprint("b" * 64))
        first  <- repository.claim(firstClaim)
        second <- repository.claim(secondClaim)
      } yield {
        val stored = processing(firstClaim)

        first shouldEqual Claimed(stored)
        second shouldEqual Existing(stored)
      }
    }

    "treat organization and actor as independent parts of the uniqueness scope" in withDatabase {
      for {
        firstOrganization  <- withOrganization()
        secondOrganization <- withOrganization()
        firstActor         <- withUser()
        secondActor        <- withUser()
        sharedKey         = WorkUnitIdempotencyKey(UUID.randomUUID())
        first             = claim(scope(firstOrganization.id, firstActor.id, sharedKey))
        otherOrganization = claim(scope(secondOrganization.id, firstActor.id, sharedKey))
        otherActor        = claim(scope(firstOrganization.id, secondActor.id, sharedKey))
        firstResult        <- repository.claim(first)
        organizationResult <- repository.claim(otherOrganization)
        actorResult        <- repository.claim(otherActor)
      } yield {
        firstResult shouldEqual Claimed(processing(first))
        organizationResult shouldEqual Claimed(processing(otherOrganization))
        actorResult shouldEqual Claimed(processing(otherActor))
      }
    }

    "persist and decode an Update target work unit ID" in withDatabase {
      for {
        organization <- withOrganization()
        actor        <- withUser()
        targetId    = WorkUnitId(UUID.randomUUID())
        updateClaim = claim(scope(organization.id, actor.id), operation = Update(targetId))
        result <- repository.claim(updateClaim)
        stored <- repository.find(updateClaim.scope)
      } yield {
        val expected = processing(updateClaim)

        result shouldEqual Claimed(expected)
        stored shouldEqual Some(expected)
        stored.map(_.operation) shouldEqual Some(Update(targetId))
      }
    }
  }

  "complete" should {
    "atomically store the response and optional result work unit ID" in withDatabase {
      for {
        organization <- withOrganization()
        actor        <- withUser()
        firstClaim  = claim(scope(organization.id, actor.id))
        secondClaim = claim(scope(organization.id, actor.id))
        _ <- repository.claim(firstClaim)
        _ <- repository.claim(secondClaim)
        resultId      = WorkUnitId(UUID.randomUUID())
        firstOutcome  = WorkUnitWriteOutcome(201, outcomeBody, Some(resultId))
        secondOutcome = WorkUnitWriteOutcome(204, Json.obj(), None)
        firstExpiry   = Instant.parse("2026-07-28T10:00:00Z")
        secondExpiry  = Instant.parse("2026-07-29T10:00:00Z")
        firstUpdated  <- repository.complete(firstClaim.scope, firstOutcome, firstExpiry)
        secondUpdated <- repository.complete(secondClaim.scope, secondOutcome, secondExpiry)
        firstStored   <- repository.find(firstClaim.scope)
        secondStored  <- repository.find(secondClaim.scope)
      } yield {
        firstUpdated shouldEqual 1
        secondUpdated shouldEqual 1
        firstStored shouldEqual Some(
          Completed(
            firstClaim.claimId,
            firstClaim.scope,
            firstClaim.operation,
            firstClaim.fingerprintVersion,
            firstClaim.canonicalRequest,
            firstClaim.fingerprint,
            firstOutcome,
            firstClaim.createdAt,
            firstExpiry
          )
        )
        secondStored shouldEqual Some(
          Completed(
            secondClaim.claimId,
            secondClaim.scope,
            secondClaim.operation,
            secondClaim.fingerprintVersion,
            secondClaim.canonicalRequest,
            secondClaim.fingerprint,
            secondOutcome,
            secondClaim.createdAt,
            secondExpiry
          )
        )
      }
    }

    "fail when the scope is missing or no longer Processing" in withDatabase {
      for {
        organization <- withOrganization()
        actor        <- withUser()
        existing     = claim(scope(organization.id, actor.id))
        missingScope = scope(organization.id, actor.id)
        outcome      = WorkUnitWriteOutcome(201, outcomeBody, None)
        expiry       = Instant.parse("2026-07-28T10:00:00Z")
        _        <- repository.claim(existing)
        _        <- repository.complete(existing.scope, outcome, expiry)
        repeated <- repository.complete(existing.scope, outcome, expiry).asTry
        missing  <- repository.complete(missingScope, outcome, expiry).asTry
      } yield {
        failedWith[WorkUnitIdempotencyCompletionInvariantViolation](repeated).updatedRows shouldEqual 0
        failedWith[WorkUnitIdempotencyCompletionInvariantViolation](missing).updatedRows shouldEqual 0
      }
    }
  }

  "redactExpired" should {
    "redact only expired Completed responses and retain permanent metadata" in withDatabase {
      val now            = Instant.parse("1900-01-02T00:00:00Z")
      val claimCreatedAt = now.minusSeconds(2L)
      val expiredAt      = now.minusSeconds(1L)
      val freshExpiresAt = now.plusSeconds(1L)

      for {
        organization <- withOrganization()
        actor        <- withUser()
        expiredClaim    = claim(scope(organization.id, actor.id), claimCreatedAt = claimCreatedAt)
        freshClaim      = claim(scope(organization.id, actor.id), claimCreatedAt = claimCreatedAt)
        processingClaim = claim(scope(organization.id, actor.id), claimCreatedAt = claimCreatedAt)
        _ <- repository.claim(expiredClaim)
        _ <- repository.claim(freshClaim)
        _ <- repository.claim(processingClaim)
        resultId       = WorkUnitId(UUID.randomUUID())
        expiredOutcome = WorkUnitWriteOutcome(201, Json.obj("expired" -> Json.fromString("payload")), Some(resultId))
        freshOutcome   = WorkUnitWriteOutcome(200, Json.obj("fresh" -> Json.fromString("payload")), None)
        _                <- repository.complete(expiredClaim.scope, expiredOutcome, expiredAt)
        _                <- repository.complete(freshClaim.scope, freshOutcome, freshExpiresAt)
        redacted         <- repository.redactExpired(now)
        expiredStored    <- repository.find(expiredClaim.scope)
        freshStored      <- repository.find(freshClaim.scope)
        processingStored <- repository.find(processingClaim.scope)
      } yield {
        redacted shouldEqual 1
        expiredStored shouldEqual Some(
          Tombstone(
            expiredClaim.claimId,
            expiredClaim.scope,
            expiredClaim.operation,
            expiredClaim.fingerprintVersion,
            expiredClaim.fingerprint,
            expiredOutcome.status,
            Some(resultId),
            expiredClaim.createdAt,
            expiredAt
          )
        )
        freshStored shouldEqual Some(
          Completed(
            freshClaim.claimId,
            freshClaim.scope,
            freshClaim.operation,
            freshClaim.fingerprintVersion,
            freshClaim.canonicalRequest,
            freshClaim.fingerprint,
            freshOutcome,
            freshClaim.createdAt,
            freshExpiresAt
          )
        )
        processingStored shouldEqual Some(processing(processingClaim))
      }
    }
  }

  "record decoding" should {
    "fail safely for an invalid stored shape in find and Existing without exposing JSON payloads" in withDatabase {
      val secret             = "do-not-leak"
      val invalidBody        = s"{\"secret\":\"$secret\"}"
      val invalidClaimId     = UUID.randomUUID()
      val idempotencyKey     = UUID.randomUUID()
      val invalidCreated     = Instant.parse("2026-07-27T10:00:00Z")
      val invalidExpiry      = Instant.parse("2026-07-28T10:00:00Z")
      val invalidCreatedText = invalidCreated.toString
      val invalidExpiryText  = invalidExpiry.toString

      for {
        organization <- withOrganization()
        actor        <- withUser()
        _ <- sqlu"""
          INSERT INTO work_unit_idempotency (
            claim_id, organization_id, actor_id, idempotency_key, operation, target_work_unit_id,
            fingerprint_version, canonical_request, fingerprint, state, outcome_status, outcome_body,
            result_work_unit_id, created_at, response_expires_at
          ) VALUES (
            $invalidClaimId, ${organization.id.asInstanceOf[UUID]}, ${actor.id.asInstanceOf[UUID]}, $idempotencyKey,
            'Create', NULL, 1, NULL, ${"c" * 64}, 'Completed', 200, $invalidBody::jsonb,
            NULL, $invalidCreatedText::timestamptz, $invalidExpiryText::timestamptz
          )
        """
        invalidScope = WorkUnitIdempotencyScope(organization.id, actor.id, WorkUnitIdempotencyKey(idempotencyKey))
        findAttempt  <- repository.find(invalidScope).asTry
        claimAttempt <- repository.claim(claim(invalidScope)).asTry
      } yield {
        val expectedError = InvalidRecordShape("Completed", missingFields = Seq("canonicalRequest"), unexpectedPresentFields = Seq.empty)
        val findError     = failedWith[WorkUnitIdempotencyRecordDecodingException](findAttempt)
        val claimError    = failedWith[WorkUnitIdempotencyRecordDecodingException](claimAttempt)

        findError.validationError shouldEqual expectedError
        claimError.validationError shouldEqual expectedError
        findError.getMessage shouldEqual
          "Stored work unit idempotency record failed validation: category=invalid-record-shape; missingFields=[canonicalRequest]; unexpectedPresentFields=[]"
        claimError.getMessage shouldEqual findError.getMessage
        findError.getMessage.contains(secret) should beFalse
        findError.getMessage.contains(invalidBody) should beFalse
        findError.getMessage.contains("Completed") should beFalse
        claimError.getMessage.contains(secret) should beFalse
        claimError.getMessage.contains(invalidBody) should beFalse
        claimError.getMessage.contains("Completed") should beFalse
        findError.toString.contains(secret) should beFalse
        findError.toString.contains(invalidBody) should beFalse
        findError.toString.contains("Completed") should beFalse
        claimError.toString.contains(secret) should beFalse
        claimError.toString.contains(invalidBody) should beFalse
        claimError.toString.contains("Completed") should beFalse
      }
    }

    "expose stable error categories without leaking raw operation, state, or target values" in {
      val rawOperation = "raw-operation-sentinel"
      val rawState     = "raw-state-sentinel"
      val rawTarget    = WorkUnitId(UUID.fromString("77777777-7777-7777-7777-777777777777"))
      val cases = Seq(
        (
          "invalid-operation-target",
          WorkUnitIdempotencyRecordDecodingException(InvalidOperationTarget(rawOperation, Some(rawTarget))),
          Seq(rawOperation, rawTarget.toString)
        ),
        ("invalid-operation", WorkUnitIdempotencyRecordDecodingException(InvalidOperation(rawOperation)), Seq(rawOperation)),
        ("invalid-state", WorkUnitIdempotencyRecordDecodingException(InvalidState(rawState)), Seq(rawState)),
        (
          "invalid-record-shape",
          WorkUnitIdempotencyRecordDecodingException(
            InvalidRecordShape(
              rawState,
              missingFields = Seq("outcomeStatus", "canonicalRequest"),
              unexpectedPresentFields = Seq("responseExpiresAt", "outcomeBody")
            )
          ),
          Seq(rawState)
        )
      )

      val missingCategories = cases.collect {
        case (category, exception, _) if !exception.getMessage.contains(s"category=$category") => category
      }
      val leaks = cases.flatMap { case (category, exception, forbiddenValues) =>
        forbiddenValues.flatMap { forbidden =>
          Seq("getMessage" -> exception.getMessage, "toString" -> exception.toString).collect {
            case (surface, value) if value.contains(forbidden) => s"$category/$surface leaked $forbidden"
          }
        }
      }
      val shapeException = cases.last._2

      missingCategories should beEmpty
      leaks should beEmpty
      shapeException.getMessage shouldEqual
        "Stored work unit idempotency record failed validation: category=invalid-record-shape; missingFields=[canonicalRequest,outcomeStatus]; unexpectedPresentFields=[outcomeBody,responseExpiresAt]"
      shapeException.toString.endsWith(shapeException.getMessage) should beTrue
    }
  }
}
