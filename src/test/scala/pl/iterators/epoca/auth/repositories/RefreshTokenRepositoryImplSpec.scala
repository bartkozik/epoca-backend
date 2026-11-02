package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AccountAuthentication.{Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.{RefreshTokenExpiredAt, RefreshTokenValue}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, RefreshToken}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant
import java.util.UUID

class RefreshTokenRepositoryImplSpec extends PgRepositorySpec {

  private val sut: RefreshTokenRepository = new RefreshTokenRepositoryImpl()
  private val userRepository              = new UserRepositoryImpl()

  val refreshTokenGenerator: Generator[RefreshToken] = allGenerators[RefreshToken].normal
  val userGenerator: Generator[User]                 = allGenerators[User].normal

  "rotation and replay revocation" should {
    "rotate a matching token, revoke a stale token, and never recreate a deleted family" in withDatabase {
      val user  = userGenerator.generate
      val now   = Instant.now()
      val token = refreshTokenGenerator.generate.copy(userId = user.id, expiredAt = Some(RefreshTokenExpiredAt(now.plusSeconds(3600))))
      val next  = token.copy(value = RefreshTokenValue("rotated"))
      for {
        _      <- userRepository.insert(user)
        _      <- sut.upsert(token)
        kept   <- sut.revokeIfStale(token)
        first  <- sut.rotate(token, next, now)
        replay <- sut.rotate(token, next.copy(value = RefreshTokenValue("loser")), now)
        absent <- sut.find(RefreshTokenFilters(userId = Some(user.id), deviceId = Some(token.deviceId)))
        after  <- sut.rotate(next, token, now)
      } yield {
        kept shouldEqual 0
        first.map(_.value) shouldEqual Some(next.value)
        replay shouldEqual None
        absent shouldEqual None
        after shouldEqual None
      }
    }
    "revoke a known device after a separately presented stale token" in withDatabase {
      val token = refreshTokenGenerator.generate
      for {
        _       <- insertRefreshTokenUsers(Seq(token))
        _       <- sut.upsert(token)
        revoked <- sut.revokeIfStale(token.copy(value = RefreshTokenValue("stale")))
        absent  <- sut.find(RefreshTokenFilters(userId = Some(token.userId), deviceId = Some(token.deviceId)))
      } yield {
        revoked shouldEqual 1
        absent shouldEqual None
      }
    }
    "reject an expired stored token even when its replacement has a later deadline" in withDatabase {
      val now   = Instant.now()
      val token = refreshTokenGenerator.generate.copy(expiredAt = Some(RefreshTokenExpiredAt(now.minusSeconds(1))))
      for {
        _      <- insertRefreshTokenUsers(Seq(token))
        _      <- sut.upsert(token)
        result <- sut.rotate(token, token.copy(expiredAt = Some(RefreshTokenExpiredAt(now.plusSeconds(3600)))), now)
        absent <- sut.find(RefreshTokenFilters(userId = Some(token.userId), deviceId = Some(token.deviceId)))
      } yield {
        result shouldEqual None
        absent shouldEqual None
      }
    }
  }

  "upsert and filter" should {
    "round-trip account proof with its original authentication time and nullable legacy metadata" in withDatabase {
      val user  = userGenerator.generate.copy(archivedAt = None)
      val proof = AccountAuthentication(Instant.parse("2026-10-02T10:00:00Z"), Credential(CredentialMethod.Token, UserIdentityId(UUID.randomUUID())))
      val token = refreshTokenGenerator.generate.copy(userId = user.id, accountAuthentication = Some(proof))
      for {
        _       <- userRepository.insert(user)
        _       <- sut.upsert(token)
        trusted <- sut.find(RefreshTokenFilters(userId = Some(user.id), deviceId = Some(token.deviceId)))
        _       <- sut.upsert(token.copy(accountAuthentication = None))
        legacy  <- sut.find(RefreshTokenFilters(userId = Some(user.id), deviceId = Some(token.deviceId)))
      } yield {
        trusted.flatMap(_.accountAuthentication) shouldEqual Some(proof)
        legacy.flatMap(_.accountAuthentication) shouldEqual None
      }
    }
    "work as follows" in withDatabase {
      val uId1 = userGenerator.generate.id
      val uId2 = userGenerator.generate.id

      val refreshToken1 = refreshTokenGenerator.generate.copy(userId = uId1)
      val refreshToken2 = refreshTokenGenerator.generate.copy(userId = uId1)
      val refreshToken3 = refreshTokenGenerator.generate.copy(userId = uId2)

      val refreshToken1Changed = refreshToken1.copy(value = refreshTokenGenerator.generate.value)

      for {
        f1 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken1.userId), deviceId = Some(refreshToken1.deviceId)))
        f2 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken2.userId), deviceId = Some(refreshToken2.deviceId)))

        l1 <- sut.list(RefreshTokenFilters())
        l2 <- sut.list(RefreshTokenFilters(userId = Some(uId1)))
        l3 <- sut.list(RefreshTokenFilters(userId = Some(uId2)))

        _ <- insertRefreshTokenUsers(Seq(refreshToken1, refreshToken2, refreshToken3))
        _ <- sut.upsert(refreshToken1)
        _ <- sut.upsert(refreshToken2)
        _ <- sut.upsert(refreshToken3)

        f3 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken1.userId), deviceId = Some(refreshToken1.deviceId)))
        f4 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken2.userId), deviceId = Some(refreshToken2.deviceId)))

        _ <- sut.upsert(refreshToken1Changed)
        _ <- sut.purge(refreshToken2.userId, refreshToken2.deviceId)

        f5 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken1.userId), deviceId = Some(refreshToken1.deviceId)))
        f6 <- sut.find(RefreshTokenFilters(userId = Some(refreshToken2.userId), deviceId = Some(refreshToken2.deviceId)))

        l4 <- sut.list(RefreshTokenFilters())
        l5 <- sut.list(RefreshTokenFilters(userId = Some(uId1)))
        l6 <- sut.list(RefreshTokenFilters(userId = Some(uId2)))

      } yield {
        f1 shouldEqual None
        f2 shouldEqual None

        l1 shouldEqual Nil
        l2 shouldEqual Nil
        l3 shouldEqual Nil

        f3 shouldEqual Some(refreshToken1)
        f4 shouldEqual Some(refreshToken2)

        f5 shouldEqual Some(refreshToken1Changed)
        f6 shouldEqual None

        l4 should containTheSameElementsAs(List(refreshToken1Changed, refreshToken3))
        l5 should containTheSameElementsAs(List(refreshToken1Changed))
        l6 should containTheSameElementsAs(List(refreshToken3))
      }
    }
  }
  "filter params" should {
    "userId" in withDatabase {
      val uId1 = userGenerator.generate.id
      val uId2 = userGenerator.generate.id
      val uId3 = userGenerator.generate.id

      val refreshToken1 = refreshTokenGenerator.generate.copy(userId = uId1)
      val refreshToken2 = refreshTokenGenerator.generate.copy(userId = uId1)
      val refreshToken3 = refreshTokenGenerator.generate.copy(userId = uId2)
      val refreshToken4 = refreshTokenGenerator.generate.copy(userId = uId3)

      for {
        _ <- insertRefreshTokenUsers(Seq(refreshToken1, refreshToken2, refreshToken3, refreshToken4))
        _ <- sut.upsert(refreshToken1)
        _ <- sut.upsert(refreshToken2)
        _ <- sut.upsert(refreshToken3)
        _ <- sut.upsert(refreshToken4)

        l1 <- sut.list(RefreshTokenFilters(userId = Some(uId1)))
        l2 <- sut.list(RefreshTokenFilters(userId = Some(uId2)))
        l3 <- sut.list(RefreshTokenFilters(userId = Some(uId3)))
      } yield {
        l1 should containTheSameElementsAs(List(refreshToken1, refreshToken2))
        l2 should containTheSameElementsAs(List(refreshToken3))
        l3 should containTheSameElementsAs(List(refreshToken4))
      }
    }
    "deviceId" in withDatabase {
      val refreshToken1 = refreshTokenGenerator.generate
      val refreshToken2 = refreshTokenGenerator.generate.copy(deviceId = refreshToken1.deviceId)
      val refreshToken3 = refreshTokenGenerator.generate
      val refreshToken4 = refreshTokenGenerator.generate

      for {
        _ <- insertRefreshTokenUsers(Seq(refreshToken1, refreshToken2, refreshToken3, refreshToken4))
        _ <- sut.upsert(refreshToken1)
        _ <- sut.upsert(refreshToken2)
        _ <- sut.upsert(refreshToken3)
        _ <- sut.upsert(refreshToken4)

        l1 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken1.deviceId)))
        l2 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken3.deviceId)))
        l3 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken4.deviceId)))
      } yield {
        l1 should containTheSameElementsAs(List(refreshToken1, refreshToken2))
        l2 should containTheSameElementsAs(List(refreshToken3))
        l3 should containTheSameElementsAs(List(refreshToken4))
      }
    }
    "value" in withDatabase {
      val refreshToken1 = refreshTokenGenerator.generate
      val refreshToken2 = refreshTokenGenerator.generate
      val refreshToken3 = refreshTokenGenerator.generate

      for {
        _ <- insertRefreshTokenUsers(Seq(refreshToken1, refreshToken2, refreshToken3))
        _ <- sut.upsert(refreshToken1)
        _ <- sut.upsert(refreshToken2)
        _ <- sut.upsert(refreshToken3)

        l1 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken1.deviceId)))
        l2 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken2.deviceId)))
        l3 <- sut.list(RefreshTokenFilters(deviceId = Some(refreshToken3.deviceId)))
      } yield {
        l1 should containTheSameElementsAs(List(refreshToken1))
        l2 should containTheSameElementsAs(List(refreshToken2))
        l3 should containTheSameElementsAs(List(refreshToken3))
      }
    }
  }

  private def insertRefreshTokenUsers(tokens: Seq[RefreshToken]): DBIO[Unit] =
    DBIO
      .sequence(tokens.map(_.userId).distinct.map(userId => userRepository.insert(userGenerator.generate.copy(id = userId))))
      .map(_ => ())
}
