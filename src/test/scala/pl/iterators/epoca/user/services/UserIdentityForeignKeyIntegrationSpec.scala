package pl.iterators.epoca.user.services

import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.{AuthContext, UserLoginToken, UserPassword}
import pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters
import pl.iterators.epoca.auth.repositories.{UserLoginTokenRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.user.services.UserDeleteService.UserDeleteResult
import pl.iterators.epoca.user.services.UserIdentityDeleteService.UserIdentityDeleteResult
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.jdbc.SetParameter

import java.sql.Types
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UserIdentityForeignKeyIntegrationSpec extends PgRepositorySpec {

  private implicit val ioRuntime: IORuntime        = IORuntime.global
  private implicit val setUuid: SetParameter[UUID] = SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private val userRepository           = new UserRepositoryImpl()
  private val userIdentityRepository   = new UserIdentityRepositoryImpl()
  private val userPasswordRepository   = new UserPasswordRepositoryImpl()
  private val userLoginTokenRepository = new UserLoginTokenRepositoryImpl()
  private val transactor               = new PostgresIOTransactor(db)

  private val userIdentityDeleteService = new UserIdentityDeleteService(transactor, userIdentityRepository)
  private val userDeleteService         = new UserDeleteService(transactor, userRepository, userIdentityRepository)

  private val userGenerator: Generator[User]                     = allGenerators[User].normal
  private val userIdentityGenerator: Generator[UserIdentity]     = allGenerators[UserIdentity].normal
  private val userPasswordGenerator: Generator[UserPassword]     = allGenerators[UserPassword].normal
  private val userLoginTokenGenerator: Generator[UserLoginToken] = allGenerators[UserLoginToken].normal
  private val authContextGenerator: Generator[AuthContext]       = allGenerators[AuthContext].normal

  "UserIdentityDeleteService" should {
    "remove a non-main identity together with its password and login token" in {
      val user     = userGenerator.generate.copy(archivedAt = None)
      val identity = userIdentityGenerator.generate.copy(userId = user.id, main = UserIdentityMain(false))
      val password = userPasswordGenerator.generate.copy(userIdentityId = identity.id)
      val token    = userLoginTokenGenerator.generate.copy(userIdentityId = identity.id)
      val auth     = authContextGenerator.generate.copy(id = user.id, fresh = true)

      try {
        run(userRepository.insert(user))
        run(userIdentityRepository.insert(identity))
        run(userPasswordRepository.upsert(password))
        run(userLoginTokenRepository.insert(token))

        val result        = userIdentityDeleteService.delete(auth, identity.id).unsafeRunSync()
        val foundIdentity = run(userIdentityRepository.find(UserIdentityFilters(id = Some(identity.id))))
        val foundPassword = run(userPasswordRepository.find(identity.id))
        val foundToken    = run(userLoginTokenRepository.find(UserLoginTokenFilters(id = Some(token.id))))

        result shouldEqual UserIdentityDeleteResult.Ok
        (foundIdentity, foundPassword, foundToken) shouldEqual (None, None, None)
      } finally
        cleanup(user.id.asInstanceOf[UUID], identity.id.asInstanceOf[UUID])
    }
  }

  "UserDeleteService" should {
    "archive a user and remove all of the identity's password and login token rows" in {
      val user     = userGenerator.generate.copy(archivedAt = None)
      val identity = userIdentityGenerator.generate.copy(userId = user.id)
      val password = userPasswordGenerator.generate.copy(userIdentityId = identity.id)
      val token    = userLoginTokenGenerator.generate.copy(userIdentityId = identity.id)
      val auth     = authContextGenerator.generate.copy(id = user.id, fresh = true)

      try {
        run(userRepository.insert(user))
        run(userIdentityRepository.insert(identity))
        run(userPasswordRepository.upsert(password))
        run(userLoginTokenRepository.insert(token))

        val result        = userDeleteService.delete(auth, user.id).unsafeRunSync()
        val archivedUser  = run(userRepository.find(UserFilters(id = Some(user.id), includeArchived = true)))
        val foundIdentity = run(userIdentityRepository.find(UserIdentityFilters(id = Some(identity.id))))
        val foundPassword = run(userPasswordRepository.find(identity.id))
        val foundToken    = run(userLoginTokenRepository.find(UserLoginTokenFilters(id = Some(token.id))))

        result shouldEqual UserDeleteResult.Ok
        archivedUser.flatMap(_.archivedAt) should beSome
        (foundIdentity, foundPassword, foundToken) shouldEqual (None, None, None)
      } finally
        cleanup(user.id.asInstanceOf[UUID], identity.id.asInstanceOf[UUID])
    }
  }

  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)

  private def cleanup(userId: UUID, userIdentityId: UUID): Unit =
    run(
      DBIO.seq(
        sqlu"""DELETE FROM user_login_token WHERE user_identity_id = $userIdentityId""",
        sqlu"""DELETE FROM user_password WHERE user_identity_id = $userIdentityId""",
        sqlu"""DELETE FROM user_identity WHERE id = $userIdentityId""",
        sqlu"""DELETE FROM "user" WHERE id = $userId"""
      )
    )
}
