package pl.iterators.epoca.auth.domain

import pl.iterators.epoca.auth.domain.AuthDomain.PasswordHash
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId

case class UserPassword(userIdentityId: UserIdentityId, password: PasswordHash)
