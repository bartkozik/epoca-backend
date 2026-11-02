package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod, Evidence}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.repositories.{AccountAuthenticationRepository, UserPasswordRepository}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserId}
import pl.iterators.epoca.user.repositories.UserIdentityRepository
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import slick.dbio.DBIO

import java.time.{Duration, Instant}
import scala.concurrent.ExecutionContext

/** Compose inside the caller's transaction, after email reservations and user locks. Mutating callers lock the user FOR UPDATE first; invitation
  * callers may hold FOR SHARE. The supplied clock is server time and refresh must preserve the original authenticatedAt.
  */
class AccountAuthenticationValidator(
  accountAuthenticationRepository: AccountAuthenticationRepository,
  userIdentityRepository: UserIdentityRepository,
  userPasswordRepository: UserPasswordRepository
)(implicit ec: ExecutionContext) {
  def validate(
    authContext: AuthContext,
    now: Instant,
    maxAge: Duration = Duration.ofMinutes(5)
  ): DBIO[Boolean] =
    authContext.accountAuthentication match {
      case Some(proof) if !proof.authenticatedAt.isAfter(now) && !proof.authenticatedAt.isBefore(now.minus(maxAge)) =>
        accountAuthenticationRepository.activeHumanForShare(authContext.id).flatMap {
          case true  => validateEvidence(authContext.id, proof.evidence)
          case false => DBIO.successful(false)
        }
      case _ => DBIO.successful(false)
    }

  private def validateEvidence(userId: UserId, evidence: Evidence): DBIO[Boolean] = evidence match {
    case bound: BoundSaml => accountAuthenticationRepository.currentSamlBindingForShare(userId, bound)
    case Credential(method, identityId) =>
      userIdentityRepository.find(UserIdentityFilters(id = Some(identityId), userId = Some(userId), forUpdate = true)).flatMap {
        case Some(identity) if identity.userId == userId =>
          val confirmedContact = (identity.`type` == IdentityType.MailAddress || identity.`type` == IdentityType.Phone) && identity.confirmed
          method match {
            case CredentialMethod.Password if confirmedContact => userPasswordRepository.find(identityId).map(_.nonEmpty)
            case CredentialMethod.Token                        => DBIO.successful(confirmedContact)
            case CredentialMethod.Firebase                     => DBIO.successful(identity.`type` == IdentityType.MailAddress && identity.confirmed)
            case CredentialMethod.TimeBasedOneTimePassword     => DBIO.successful(identity.`type` == IdentityType.TimeBasedOneTimePassword)
            case CredentialMethod.CryptoVerification           => DBIO.successful(identity.`type` == IdentityType.CryptoVerification)
            case _                                             => DBIO.successful(false)
          }
        case _ => DBIO.successful(false)
      }
  }
}
