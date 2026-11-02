package pl.iterators.epoca.auth.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId

import java.time.Instant

/** Server-issued evidence of account authentication, independent of invitation recipient proof. */
final case class AccountAuthentication(authenticatedAt: Instant, evidence: AccountAuthentication.Evidence)

object AccountAuthentication {
  sealed trait Evidence
  final case class Credential(method: CredentialMethod, identityId: UserIdentityId) extends Evidence
  final case class BoundSaml(
    organizationId: OrganizationId,
    trustEpoch: Long,
    issuer: String,
    subject: String)
      extends Evidence

  sealed trait CredentialMethod extends EnumEntry
  object CredentialMethod extends Enum[CredentialMethod] {
    case object Password                 extends CredentialMethod
    case object Token                    extends CredentialMethod
    case object TimeBasedOneTimePassword extends CredentialMethod
    case object CryptoVerification       extends CredentialMethod
    case object Firebase                 extends CredentialMethod
    val values: IndexedSeq[CredentialMethod] = findValues
  }
}
