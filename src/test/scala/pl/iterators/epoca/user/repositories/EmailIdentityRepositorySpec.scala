package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.utils.PgRepositorySpec
import slick.dbio.DBIO

import java.time.Instant

class EmailIdentityRepositorySpec extends PgRepositorySpec {
  private val repository = new EmailIdentityRepositoryImpl
  private val users      = new UserRepositoryImpl
  private val identities = new UserIdentityRepositoryImpl
  private def user       = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
  private def identity(
    u: User,
    kind: IdentityType,
    value: String,
    confirmed: Boolean = true
  ) =
    allGenerators[UserIdentity].normal.generate.copy(
      userId = u.id,
      `type` = kind,
      value = UserIdentityValue(value),
      confirmed = UserIdentityConfirmed(confirmed)
    )

  "email resolution" should {
    "resolve a confirmed SAML identity case-insensitively and trim spaces" in {
      val u = user
      val i = identity(u, IdentityType.Saml, " Anna@example.test ")
      withDatabase(for {
        _      <- users.insert(u)
        _      <- identities.insert(i)
        result <- repository.resolve("anna@example.test")
      } yield result) shouldEqual Resolution.Single(u, Seq(i))
    }
    Seq(IdentityType.MailAddress, IdentityType.Saml).foreach { kind =>
      s"resolve legacy $kind addresses padded by every PostgreSQL-supported JVM trim character" in {
        (1 to 32).foreach { code =>
          val u       = user
          val padding = code.toChar.toString
          val i       = identity(u, kind, padding + "Anna@example.test" + padding)
          val (clean, padded) = withDatabase(for {
            _      <- users.insert(u)
            _      <- identities.insert(i)
            clean  <- repository.resolve("anna@example.test")
            padded <- repository.resolve(i.value)
          } yield (clean, padded))
          assert(clean == Resolution.Single(u, Seq(i)), s"Stored U+${code.toHexString} padding must be removed")
          assert(padded == clean, s"Stored and requested U+${code.toHexString} padding must agree")
        }
        ok
      }
    }
    "return Empty only for an unreserved address" in {
      withDatabase(repository.resolve("unreserved@example.test")) shouldEqual Resolution.Empty
    }
    "retain unconfirmed, archived, and service-account reservations" in {
      val cases = Seq(
        (user, false),
        (user.copy(archivedAt = Some(UserArchivedAt(Instant.parse("2026-01-01T12:00:00Z")))), true),
        (user.copy(accountType = UserAccountType.ServiceAccount), true)
      )
      cases.foreach { case (u, confirmed) =>
        val i = identity(u, IdentityType.MailAddress, "Reserved@example.test", confirmed)
        assert(withDatabase(for {
          _      <- users.insert(u)
          _      <- identities.insert(i)
          result <- repository.resolve(" RESERVED@example.test ")
        } yield result) == Resolution.Single(u, Seq(i)))
      }
      ok
    }
    "report different users as Ambiguous across email kinds" in {
      val a = user
      val b = user
      withDatabase(for {
        _      <- users.insert(a)
        _      <- users.insert(b)
        _      <- identities.insert(identity(a, IdentityType.MailAddress, "anna@example.test"))
        _      <- identities.insert(identity(b, IdentityType.Saml, "Anna@example.test"))
        result <- repository.resolve("anna@example.test")
      } yield result) shouldEqual Resolution.Ambiguous
    }
    "keep multiple matching identities of one user Single" in {
      val u = user
      val a = identity(u, IdentityType.MailAddress, "anna@example.test")
      val b = identity(u, IdentityType.Saml, "Anna@example.test")
      val result = withDatabase(for {
        _      <- users.insert(u)
        _      <- identities.insert(a)
        _      <- identities.insert(b)
        result <- repository.resolve("anna@example.test")
      } yield result)
      result shouldEqual Resolution.Single(u, Seq(a, b).sortBy(_.id.toString))
    }
    "fold ASCII case only and preserve legacy Unicode code points" in {
      val cases = Seq(
        " I@EXAMPLE.TEST "          -> "i@example.test",
        "\tİI@EXAMPLE.TEST\n"       -> "İi@example.test",
        "Σσς@EXAMPLE.TEST"          -> "Σσς@example.test",
        "KK@EXAMPLE.TEST"           -> "Kk@example.test",
        " D’ANNA+İΣK@EXAMPLE.TEST " -> "d’anna+İΣK@example.test"
      )
      cases.foreach { case (input, expected) => assert(EmailIdentityRepository.canonical(input) == expected) }
      ok
    }
    Seq(IdentityType.MailAddress, IdentityType.Saml).foreach { kind =>
      s"resolve distinct Unicode $kind legacy addresses without assigning ASCII or Greek lookalikes" in {
        val fixtures = Seq("İ", "i", "Σ", "σ", "ς", "K", "k").map { local =>
          val u = user
          (u, identity(u, kind, local + "@example.test"))
        }
        val results = withDatabase(for {
          _       <- DBIO.sequence(fixtures.map { case (u, i) => users.insert(u).flatMap(_ => identities.insert(i)) })
          results <- DBIO.sequence(fixtures.map { case (_, i) => repository.resolve(i.value) })
        } yield results)
        assert(results == fixtures.map { case (u, i) => Resolution.Single(u, Seq(i)) })
        ok
      }
    }
    "preserve dots and plus tags when canonicalizing" in {
      EmailIdentityRepository.canonical(" A.Nna+Tag@Example.Test ") shouldEqual "a.nna+tag@example.test"
    }
  }
}
