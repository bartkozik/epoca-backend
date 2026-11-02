package pl.iterators.epoca.auth.services

import com.typesafe.config.{Config => TsConfig}
import org.mindrot.jbcrypt.BCrypt
import pl.iterators.epoca.auth.domain.AuthDomain.{LoginTokenHash, LoginTokenPlain}

class LoginTokenHashService(config: LoginTokenHashService.Config) {

  def hash(loginTokenPlain: LoginTokenPlain): LoginTokenHash =
    LoginTokenHash(hashPasswordStable(loginTokenPlain))

  private def hashPasswordStable(password: String): String = BCrypt.hashpw(password, config.stableSalt)
}

object LoginTokenHashService {

  case class Config(stableSalt: String)
  object Config {
    def apply(tsConfig: TsConfig): Config =
      Config(stableSalt = tsConfig.getString("user.loginToken.stableSalt"))
  }

}
