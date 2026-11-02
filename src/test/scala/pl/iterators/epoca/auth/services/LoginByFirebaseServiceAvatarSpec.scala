package pl.iterators.epoca.auth.services

import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters._

class LoginByFirebaseServiceAvatarSpec extends Specification {
  private def pictureUrl(provider: Option[String], picture: Option[String]): Option[String] = {
    val claims = provider.fold(Map.empty[String, AnyRef])(p => Map("firebase" -> Map[String, AnyRef]("sign_in_provider" -> p).asJava))
    LoginByFirebaseService.googlePictureUrl(claims.asJava, picture.orNull)
  }

  "googlePictureUrl" should {
    "return the picture for a Google sign-in" in {
      pictureUrl(Some("google.com"), Some(" https://lh3.googleusercontent.com/a ")) must beSome("https://lh3.googleusercontent.com/a")
    }
    "ignore other providers" in {
      pictureUrl(Some("password"), Some("https://lh3.googleusercontent.com/a")) must beNone
    }
    "ignore tokens without firebase claims" in {
      pictureUrl(None, Some("https://lh3.googleusercontent.com/a")) must beNone
    }
    "ignore a missing or blank picture" in {
      pictureUrl(Some("google.com"), None) must beNone
      pictureUrl(Some("google.com"), Some("  ")) must beNone
    }
  }
}
