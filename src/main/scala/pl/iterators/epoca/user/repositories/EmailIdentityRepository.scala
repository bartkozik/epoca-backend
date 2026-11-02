package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.utils.db.EmailCanonicalization
import slick.dbio.DBIO

trait EmailIdentityRepository {

  /** Acquire before any row locks, in the same transaction as resolution and writes. */
  def lock(email: String): DBIO[Unit]

  /** Includes archived accounts and unconfirmed identities: reservation is not authentication proof. */
  def resolve(email: String): DBIO[EmailIdentityRepository.Resolution]
}

object EmailIdentityRepository {
  def canonical(email: String): String = EmailCanonicalization.canonical(email)

  sealed trait Resolution
  object Resolution {
    case object Empty                                                  extends Resolution
    final case class Single(user: User, identities: Seq[UserIdentity]) extends Resolution
    case object Ambiguous                                              extends Resolution
  }
}
