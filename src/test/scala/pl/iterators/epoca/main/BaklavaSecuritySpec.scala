package pl.iterators.epoca.main

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

class BaklavaSecuritySpec extends Specification {
  private def config(user: String, password: String) = ConfigFactory.parseString(s"""
    baklavaRoutes.enabled=true
    baklavaRoutes.basicAuthUser="$user"
    baklavaRoutes.basicAuthPassword="$password"
  """)

  "Baklava startup security" should {
    "allow disabled documentation without credentials" in {
      BaklavaSecurity.validate(ConfigFactory.parseString("baklavaRoutes.enabled=false")) must not(throwA[Exception])
      BaklavaSecurity.validate(ConfigFactory.empty()) must not(throwA[Exception])
    }
    "reject missing credentials when documentation is enabled" in {
      BaklavaSecurity.validate(ConfigFactory.parseString("baklavaRoutes.enabled=true")) must throwA[IllegalArgumentException]
    }
    "reject blank or default credentials independently" in {
      Seq("", " ", "admin", " Admin ").forall { user =>
        scala.util.Try(BaklavaSecurity.validate(config(user, "unique-password"))).isFailure
      } must beTrue
      Seq("", " ", "password", " PASSWORD ").forall { password =>
        scala.util.Try(BaklavaSecurity.validate(config("docs-operator", password))).isFailure
      } must beTrue
    }
    "allow explicitly configured nondefault credentials" in {
      BaklavaSecurity.validate(config("docs-operator", "unique-password")) must not(throwA[Exception])
    }
  }
}
