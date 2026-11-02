package pl.iterators.epoca.user.services

import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.auth.domain.UserPassword
import pl.iterators.epoca.auth.repositories.UserPasswordRepository
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserIdentityRepository, UserRepository}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

/** Composable account creation. The caller must execute this action transactionally, keeping the email reservation until its entire transaction
  * commits. No external effects.
  */
class UserCreationActions(
  userRepository: UserRepository,
  userIdentityRepository: UserIdentityRepository,
  emailIdentityRepository: EmailIdentityRepository,
  userPasswordRepository: UserPasswordRepository
)(implicit ec: ExecutionContext) {

  /** None means the identity is reserved. Password hashes must be computed before starting the transaction. */
  def create(
    user: User,
    identity: UserIdentity,
    initialPasswordHash: Option[PasswordHash]
  ): DBIO[Option[(User, UserIdentity)]] = {
    val available = identity.`type` match {
      case IdentityType.MailAddress | IdentityType.Saml =>
        emailIdentityRepository.lock(identity.value).flatMap(_ => emailIdentityRepository.resolve(identity.value)).map(_ == Resolution.Empty)
      case IdentityType.Phone => userRepository.find(UserFilters(phoneNumber = Some(PhoneNumber(identity.value)))).map(_.isEmpty)
      case _                  => DBIO.successful(false)
    }
    for {
      free <- available
      created <- if (!free) DBIO.successful(None)
                 else
                   for {
                     insertedUser     <- userRepository.insert(user)
                     insertedIdentity <- userIdentityRepository.insert(identity)
                     _ <- initialPasswordHash.fold[DBIO[Unit]](DBIO.successful(())) { hash =>
                            userPasswordRepository.upsert(UserPassword(insertedIdentity.id, hash)).map(_ => ())
                          }
                   } yield Some((insertedUser, insertedIdentity))
    } yield created
  }
}
