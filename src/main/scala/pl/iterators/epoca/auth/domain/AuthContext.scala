package pl.iterators.epoca.auth.domain

import pl.iterators.epoca.auth.domain.AuthDomain.AuthLevel
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.UserIdentityShortDto

import java.time.Instant

final case class AuthContext(
  id: UserId,
  isSuperAdmin: UserIsSuperAdmin,
  identities: Seq[UserIdentityShortDto],
  authedBy: Set[AuthContextAuthedBy],
  roles: Seq[AuthContext.OrganizationRoles],
  lastAuthedAt: Instant,
  fresh: Boolean,
  accountAuthentication: Option[AccountAuthentication] = None) {
  lazy val level: AuthLevel = AuthLevel(authedBy.map(_.`type`).size)

  lazy val isAdmin: Boolean = isSuperAdmin

  lazy val mailAddresses: Seq[MailAddress] =
    identities
      .filter(_.`type` == IdentityType.MailAddress)
      .flatMap(_.value.map(v => MailAddress(v)))

  lazy val phones: Seq[PhoneNumber] =
    identities
      .filter(_.`type` == IdentityType.Phone)
      .flatMap(_.value.map(v => PhoneNumber(v)))
}

object AuthContext {
  case class OrganizationRoles(organizationId: OrganizationId, roles: Seq[OrganizationUserRole])

  object OrganizationRoles {
    def apply(organizationUser: OrganizationUser): OrganizationRoles = OrganizationRoles(organizationUser.organizationId, organizationUser.roles)
  }
}

final case class AuthContextAuthedBy(`type`: IdentityType, id: Option[UserIdentityId])
