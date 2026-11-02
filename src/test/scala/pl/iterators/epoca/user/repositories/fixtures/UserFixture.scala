package pl.iterators.epoca.user.repositories.fixtures

import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait UserFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioUserRepository: UserRepositoryImpl = new UserRepositoryImpl()

  private val _userGenerator = allGenerators[User].normal
  def withUser(obj: User = _userGenerator.generate): DBIO[User] =
    _dbioUserRepository.insert(obj)
}
