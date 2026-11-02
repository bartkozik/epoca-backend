package pl.iterators.epoca.saml.repositories

import io.circe.Json
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.saml.domain.{SamlBindingKey, SamlIdentityBinding}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec

import java.time.Instant

class SamlIdentityBindingRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val rep = new SamlIdentityBindingRepositoryImpl()
  "trusted SAML bindings" should {
    "retain all provenance and match the full organization epoch issuer subject key" in withDatabase {
      for {
        org      <- withOrganization()
        otherOrg <- withOrganization()
        user     <- withUser()
        key = SamlBindingKey(org.id, 7L, "https://idp.example.test/entity", "stable-subject")
        expected = SamlIdentityBinding(
                     key,
                     user.id,
                     "person@example.test",
                     Instant.parse("2026-10-02T10:00:00Z"),
                     Json.obj("source" -> Json.fromString("explicit-invitation-activation"))
                   )
        _                  <- rep.insert(expected)
        exact              <- rep.find(key)
        locked             <- rep.lock(key)
        substitutedOrg     <- rep.find(key.copy(organizationId = otherOrg.id))
        substitutedEpoch   <- rep.find(key.copy(trustEpoch = 8L))
        substitutedIssuer  <- rep.find(key.copy(issuer = "other-issuer"))
        substitutedSubject <- rep.find(key.copy(subject = "other-subject"))
      } yield {
        exact shouldEqual Some(expected)
        locked shouldEqual Some(expected)
        Vector(substitutedOrg, substitutedEpoch, substitutedIssuer, substitutedSubject) shouldEqual Vector.fill(4)(None)
      }
    }
  }
}
