package pl.iterators.epoca.oauth.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain.{
  OAuthAuditFailureCategory,
  OAuthClientCreatedAt,
  OAuthClientEffectiveStatus,
  OAuthClientExpiresAt,
  OAuthClientId,
  OAuthClientModifiedAt,
  OAuthClientName,
  OAuthClientPolicyVersion,
  OAuthClientPublicId,
  OAuthClientSecretHash,
  OAuthClientSecretRotatedAt,
  OAuthClientSecretVersion,
  OAuthClientStatus,
  OAuthClientVersion,
  OAuthProjectAccessMode
}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.time.Instant
import java.util.UUID

class OAuthClientLifecycleSpec extends SpecificationLike {
  private val expiry = Instant.parse("2026-08-21T10:00:00Z")

  "OAuth client lifecycle" should {
    "remain active when no expiry is configured" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Active, None, expiry) shouldEqual OAuthClientEffectiveStatus.Active
    }

    "remain active before its expiry" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Active, Some(OAuthClientExpiresAt(expiry)), expiry.minusNanos(1)) shouldEqual
        OAuthClientEffectiveStatus.Active
    }

    "be expired at the exact expiry boundary" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Active, Some(OAuthClientExpiresAt(expiry)), expiry) shouldEqual
        OAuthClientEffectiveStatus.Expired
    }

    "be expired after the expiry boundary" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Active, Some(OAuthClientExpiresAt(expiry)), expiry.plusNanos(1)) shouldEqual
        OAuthClientEffectiveStatus.Expired
    }

    "keep revoked status effective before expiry" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Revoked, Some(OAuthClientExpiresAt(expiry)), expiry.minusNanos(1)) shouldEqual
        OAuthClientEffectiveStatus.Revoked
    }

    "keep revoked status effective at expiry" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Revoked, Some(OAuthClientExpiresAt(expiry)), expiry) shouldEqual
        OAuthClientEffectiveStatus.Revoked
    }

    "keep revoked status effective after expiry" in {
      OAuthClientEffectiveStatus.resolve(OAuthClientStatus.Revoked, Some(OAuthClientExpiresAt(expiry)), expiry.plusNanos(1)) shouldEqual
        OAuthClientEffectiveStatus.Revoked
    }

    "contain only the persisted active and revoked statuses" in {
      OAuthClientStatus.values shouldEqual Seq(OAuthClientStatus.Active, OAuthClientStatus.Revoked)
    }

    "use the expired-client audit failure category name" in {
      OAuthAuditFailureCategory.ExpiredClient.entryName shouldEqual "expired-client"
    }

    "redact public client IDs and secret hashes in toString" in {
      val client = OAuthClient(
        id = OAuthClientId(UUID.randomUUID()),
        clientId = OAuthClientPublicId("public-client-id-secret"),
        organizationId = OrganizationId(UUID.randomUUID()),
        serviceAccountUserId = UserId(UUID.randomUUID()),
        name = OAuthClientName("Lifecycle test client"),
        status = OAuthClientStatus.Active,
        projectAccessMode = OAuthProjectAccessMode.AllProjects,
        secretHash = OAuthClientSecretHash("bcrypt-secret-hash"),
        secretVersion = OAuthClientSecretVersion(1L),
        version = OAuthClientVersion(1L),
        policyVersion = OAuthClientPolicyVersion(1L),
        createdAt = OAuthClientCreatedAt(expiry),
        createdBy = UserId(UUID.randomUUID()),
        modifiedAt = OAuthClientModifiedAt(expiry),
        modifiedBy = UserId(UUID.randomUUID()),
        secretRotatedAt = OAuthClientSecretRotatedAt(expiry),
        secretRotatedBy = UserId(UUID.randomUUID()),
        revokedAt = None,
        revokedBy = None
      )
      val rendered = client.toString

      rendered.contains("public-client-id-secret") shouldEqual false
      rendered.contains("bcrypt-secret-hash") shouldEqual false
      rendered.contains("clientId=<redacted>") shouldEqual true
      rendered.contains("secretHash=<redacted>") shouldEqual true
    }
  }
}
