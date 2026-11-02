package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.auth.domain.AccountAuthentication
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.saml.domain.SamlDomain.SamlOneTimeToken
import pl.iterators.epoca.saml.domain.SamlRequest
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec

import java.time.Instant
import java.util.UUID

class SamlRequestRepositoryImplSpec extends PgRepositorySpec with UserFixture with OrganizationFixture {
  private val rep: SamlRequestRepository = new SamlRequestRepositoryImpl()

  val samlRequestGenerator: Generator[SamlRequest] = allGenerators[SamlRequest].normal

  "callback token consumption" should {
    "consume exactly once and require the matching persisted request" in withDatabase {
      val token = SamlOneTimeToken(UUID.randomUUID())
      for {
        user <- withUser()
        request <- rep.insert(
                     samlRequestGenerator.generate
                       .copy(userId = Some(user.id), token = Some(token), used = pl.iterators.epoca.saml.domain.SamlDomain.SamlRequestUsed(false))
                   )
        wrong  <- rep.consume(samlRequestGenerator.generate.id, token)
        first  <- rep.consume(request.id, token)
        second <- rep.consume(request.id, token)
        stored <- rep.lock(request.id)
      } yield {
        (wrong, first, second) shouldEqual (false, true, false)
        stored.map(_.used: Boolean) shouldEqual Some(true)
      }
    }
  }
  "trusted request metadata" should {
    "round-trip organization trust browser nonce and validated subject without losing prior account evidence" in withDatabase {
      val now = Instant.parse("2026-10-02T10:00:00Z")
      for {
        org  <- withOrganization()
        user <- withUser()
        proof = AccountAuthentication(now, BoundSaml(org.id, 3L, "previous-issuer", "previous-subject"))
        expected = samlRequestGenerator.generate.copy(
                     userId = Some(user.id),
                     accountAuthentication = Some(proof),
                     organizationId = Some(org.id),
                     trustEpoch = Some(4L),
                     purpose = Some("Login"),
                     browserNonceHash = Some(Vector.fill(32)(7.toByte)),
                     verifiedIssuer = Some("current-issuer"),
                     verifiedSubject = Some("stable-idp-subject"),
                     verifiedCanonicalEmail = Some("recipient@example.test"),
                     verifiedAt = Some(now)
                   )
        _      <- rep.insert(expected)
        stored <- rep.find(expected.id)
      } yield stored shouldEqual Some(expected)
    }
  }
  "CRUD" should {
    "round-trip optional account evidence without renewing its timestamp" in withDatabase {
      val proof = AccountAuthentication(Instant.parse("2026-10-02T10:00:00Z"), BoundSaml(OrganizationId(UUID.randomUUID()), 7L, "issuer", "subject"))
      for {
        user    <- withUser()
        request <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user.id), accountAuthentication = Some(proof)))
        stored  <- rep.find(request.id)
        _       <- rep.update(request.copy(accountAuthentication = None))
        legacy  <- rep.find(request.id)
      } yield {
        stored.flatMap(_.accountAuthentication) shouldEqual Some(proof)
        legacy.flatMap(_.accountAuthentication) shouldEqual None
      }
    }
    "work as follows" in withDatabase {
      for {
        user1 <- withUser()
        user2 <- withUser()
        user3 <- withUser()

        r1 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user1.id)))
        r2 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user2.id)))
        r3 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user3.id)))

        f1 <- rep.find(r1.id)
        f2 <- rep.find(r2.id)
        f3 <- rep.find(r3.id)
        f4 <- rep.find(samlRequestGenerator.generate.id)

        ru1 = r1.copy(token = samlRequestGenerator.generate.token)
        ru2 = r2.copy(createdAt = samlRequestGenerator.generate.createdAt)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        fu1 <- rep.find(r1.id)
        fu2 <- rep.find(r2.id)
        fu3 <- rep.find(r3.id)
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
        f4 shouldEqual None

        fu1 shouldEqual Some(ru1)
        fu2 shouldEqual Some(ru2)
        fu3 shouldEqual Some(r3)
      }
    }
  }
  "find" should {
    "find by SamlRequestId" in withDatabase {
      for {
        user <- withUser()

        r1 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user.id)))
        r2 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user.id)))
        r3 <- rep.insert(samlRequestGenerator.generate.copy(userId = Some(user.id)))

        f1 <- rep.find(r1.id)
        f2 <- rep.find(r2.id)
        f3 <- rep.find(r3.id)
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
      }
    }
    "find by SamlOneTimeToken" in withDatabase {
      val token1 = SamlOneTimeToken(UUID.randomUUID())
      val token2 = SamlOneTimeToken(UUID.randomUUID())
      val token3 = SamlOneTimeToken(UUID.randomUUID())
      for {
        user <- withUser()

        r1 <- rep.insert(samlRequestGenerator.generate.copy(token = Some(token1), userId = Some(user.id)))
        r2 <- rep.insert(samlRequestGenerator.generate.copy(token = Some(token2), userId = Some(user.id)))
        r3 <- rep.insert(samlRequestGenerator.generate.copy(token = Some(token3), userId = Some(user.id)))

        f1 <- rep.find(token1)
        f2 <- rep.find(token2)
        f3 <- rep.find(token3)
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
      }
    }
  }
}
