package pl.iterators.epoca.auth.domain

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.user.domain.UserDomain.{UserId, UserIdentityId}

case class RefreshToken(
  userId: UserId,
  deviceId: DeviceId,
  userAgent: Option[RefreshTokenUserAgent],
  value: RefreshTokenValue,
  authedBy: Seq[UserIdentityId],
  lastAuthedAt: RefreshTokenLastUsedAt,
  createdAt: RefreshTokenCreatedAt,
  expiredAt: Option[RefreshTokenExpiredAt],
  accountAuthentication: Option[AccountAuthentication] = None)
