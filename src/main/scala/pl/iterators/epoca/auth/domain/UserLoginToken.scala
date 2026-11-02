package pl.iterators.epoca.auth.domain

import pl.iterators.epoca.auth.domain.AuthDomain.{LoginTokenHash, LoginTokenId, LoginTokenValidTo}
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId

case class UserLoginToken(
  id: LoginTokenId,
  userIdentityId: UserIdentityId,
  loginToken: LoginTokenHash,
  validTo: Option[LoginTokenValidTo])
