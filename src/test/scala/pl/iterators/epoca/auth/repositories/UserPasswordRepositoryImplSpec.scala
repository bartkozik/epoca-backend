package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

class UserPasswordRepositoryImplSpec extends PgRepositorySpec {

  private val sut: UserPasswordRepository = new UserPasswordRepositoryImpl()
  private val userRepository              = new UserRepositoryImpl()
  private val userIdentityRepository      = new UserIdentityRepositoryImpl()

  val userPasswordGenerator: Generator[UserPassword] = allGenerators[UserPassword].normal
  val userGenerator: Generator[User]                 = allGenerators[User].normal
  val userIdentityGenerator: Generator[UserIdentity] = allGenerators[UserIdentity].normal

  "upsert and filter" should {
    "work as follows" in withDatabase {
      val userPassword1 = userPasswordGenerator.generate
      val userPassword2 = userPasswordGenerator.generate

      val userPassword1Changed = userPassword1.copy(password = userPasswordGenerator.generate.password)

      for {
        f1 <- sut.find(userPassword1.userIdentityId)
        f2 <- sut.find(userPassword2.userIdentityId)

        _ <- insertIdentityParents(Seq(userPassword1, userPassword2))
        _ <- sut.upsert(userPassword1)
        _ <- sut.upsert(userPassword2)

        f3 <- sut.find(userPassword1.userIdentityId)
        f4 <- sut.find(userPassword2.userIdentityId)

        _ <- sut.upsert(userPassword1Changed)

        f5 <- sut.find(userPassword1.userIdentityId)
        f6 <- sut.find(userPassword2.userIdentityId)

      } yield {
        f1 shouldEqual None
        f2 shouldEqual None

        f3 shouldEqual Some(userPassword1)
        f4 shouldEqual Some(userPassword2)

        f5 shouldEqual Some(userPassword1Changed)
        f6 shouldEqual Some(userPassword2)
      }
    }
  }

  private def insertIdentityParents(passwords: Seq[UserPassword]): DBIO[Unit] =
    DBIO
      .sequence(passwords.map(_.userIdentityId).distinct.map { identityId =>
        val user     = userGenerator.generate
        val identity = userIdentityGenerator.generate.copy(id = identityId, userId = user.id)

        userRepository.insert(user).andThen(userIdentityRepository.insert(identity))
      })
      .map(_ => ())
}
