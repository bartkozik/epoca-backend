package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityValue
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant

class UserLoginTokenRepositoryImplSpec extends PgRepositorySpec {

  private val sut: UserLoginTokenRepository = new UserLoginTokenRepositoryImpl()
  private val userRepository                = new UserRepositoryImpl()
  private val userIdentityRepository        = new UserIdentityRepositoryImpl()

  val userLoginTokenGenerator: Generator[UserLoginToken] = allGenerators[UserLoginToken].normal
  val userGenerator: Generator[User]                     = allGenerators[User].normal
  val userIdentityGenerator: Generator[UserIdentity]     = allGenerators[UserIdentity].normal

  "CRUd" should {
    "work as follows" in withDatabase {
      val userLoginToken1 = userLoginTokenGenerator.generate
      val userLoginToken2 = userLoginTokenGenerator.generate

      for {
        f1 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken1.id)))
        f2 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken2.id)))

        _ <- insertIdentityParents(Seq(userLoginToken1, userLoginToken2))
        _ <- sut.insert(userLoginToken1)
        _ <- sut.insert(userLoginToken2)

        f3 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken1.id)))
        f4 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken2.id)))

        _ <- sut.purge(UserLoginTokenFilters(id = Some(userLoginToken1.id)))

        f5 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken1.id)))
        f6 <- sut.find(UserLoginTokenFilters(id = Some(userLoginToken2.id)))
      } yield {
        f1 shouldEqual None
        f2 shouldEqual None

        f3 shouldEqual Some(userLoginToken1)
        f4 shouldEqual Some(userLoginToken2)

        f5 shouldEqual None
        f6 shouldEqual Some(userLoginToken2)
      }
    }
  }

  "filter by provided params" should {
    "id" in withDatabase {
      val userLoginToken1 = userLoginTokenGenerator.generate
      val userLoginToken2 = userLoginTokenGenerator.generate
      val userLoginToken3 = userLoginTokenGenerator.generate

      for {
        _  <- insertIdentityParents(Seq(userLoginToken1, userLoginToken2, userLoginToken3))
        r1 <- sut.insert(userLoginToken1)
        r2 <- sut.insert(userLoginToken2)
        r3 <- sut.insert(userLoginToken3)

        f1 <- sut.find(UserLoginTokenFilters(id = Some(r1.id)))
        f2 <- sut.find(UserLoginTokenFilters(id = Some(r2.id)))
        f3 <- sut.find(UserLoginTokenFilters(id = Some(r3.id)))
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
      }
    }
    "loginToken" in withDatabase {
      val userLoginToken1 = userLoginTokenGenerator.generate
      val userLoginToken2 = userLoginTokenGenerator.generate
      val userLoginToken3 = userLoginTokenGenerator.generate

      for {
        _  <- insertIdentityParents(Seq(userLoginToken1, userLoginToken2, userLoginToken3))
        r1 <- sut.insert(userLoginToken1)
        r2 <- sut.insert(userLoginToken2)
        r3 <- sut.insert(userLoginToken3)

        f1 <- sut.find(UserLoginTokenFilters(loginToken = Some(r1.loginToken)))
        f2 <- sut.find(UserLoginTokenFilters(loginToken = Some(r2.loginToken)))
        f3 <- sut.find(UserLoginTokenFilters(loginToken = Some(r3.loginToken)))
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
      }
    }
    "validToBefore and validToAfterOrNone" in withDatabase {
      val now       = Instant.ofEpochMilli(System.currentTimeMillis())
      val beforeNow = LoginTokenValidTo(now.minusSeconds(1))
      val afterNow  = LoginTokenValidTo(now.plusSeconds(1))
      val token     = userLoginTokenGenerator.generate.copy(validTo = Some(LoginTokenValidTo(now)))

      for {
        _ <- insertIdentityParents(Seq(token))
        r <- sut.insert(token)

        fb1 <- sut.find(UserLoginTokenFilters(validToBefore = Some(beforeNow)))
        fa1 <- sut.find(UserLoginTokenFilters(validToAfterOrNone = Some(beforeNow)))

        fb2 <- sut.find(UserLoginTokenFilters(validToBefore = Some(afterNow)))
        fa2 <- sut.find(UserLoginTokenFilters(validToAfterOrNone = Some(afterNow)))
      } yield {
        fb1 shouldEqual None
        fa1 shouldEqual Some(r)

        fb2 shouldEqual Some(r)
        fa2 shouldEqual None
      }
    }

    "validToBefore and validToAfterOrNone for None" in withDatabase {
      val now       = Instant.ofEpochMilli(System.currentTimeMillis())
      val beforeNow = LoginTokenValidTo(now.minusSeconds(1))
      val afterNow  = LoginTokenValidTo(now.plusSeconds(1))
      val token     = userLoginTokenGenerator.generate.copy(validTo = None)

      for {
        _ <- insertIdentityParents(Seq(token))
        r <- sut.insert(token)

        fb1 <- sut.find(UserLoginTokenFilters(validToBefore = Some(beforeNow)))
        fa1 <- sut.find(UserLoginTokenFilters(validToAfterOrNone = Some(beforeNow)))

        fb2 <- sut.find(UserLoginTokenFilters(validToBefore = Some(afterNow)))
        fa2 <- sut.find(UserLoginTokenFilters(validToAfterOrNone = Some(afterNow)))
      } yield {
        fb1 shouldEqual None
        fa1 shouldEqual Some(r)

        fb2 shouldEqual None
        fa2 shouldEqual Some(r)
      }
    }
  }

  private def insertIdentityParents(tokens: Seq[UserLoginToken]): DBIO[Unit] =
    DBIO
      .sequence(tokens.map(_.userIdentityId).distinct.map { identityId =>
        val user     = userGenerator.generate
        val identity = userIdentityGenerator.generate.copy(id = identityId, userId = user.id, value = UserIdentityValue(identityId.toString))

        userRepository.insert(user).andThen(userIdentityRepository.insert(identity))
      })
      .map(_ => ())

}
