package pl.iterators.epoca.utils

import pl.iterators.epoca.auth.domain.{AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIsSuperAdmin}

object AuthContextProvider extends Generators {

  val authContextGenerator: AllGenerators[AuthContext] = allGenerators[AuthContext]

  private def authLevelSet1 = Set(AuthContextAuthedBy(IdentityType.Phone, None))
  private def authLevelSet2 = Set(AuthContextAuthedBy(IdentityType.Phone, None), AuthContextAuthedBy(IdentityType.CryptoVerification, None))

  def user: AuthContext    = authContextGenerator.normal.generate.copy(roles = Seq(), authedBy = authLevelSet1)
  def user2FA: AuthContext = authContextGenerator.normal.generate.copy(roles = Seq(), authedBy = authLevelSet2)
  def admin: AuthContext   = authContextGenerator.normal.generate.copy(roles = Seq(), isSuperAdmin = UserIsSuperAdmin(true), authedBy = authLevelSet1)

}
