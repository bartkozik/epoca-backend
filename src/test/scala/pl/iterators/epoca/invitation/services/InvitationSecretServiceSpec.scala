package pl.iterators.epoca.invitation.services

import org.specs2.mutable.SpecificationLike

import java.util.Base64

class InvitationSecretServiceSpec extends SpecificationLike {
  private val service = new InvitationSecretService

  "InvitationSecretService.generate" should {
    "create 32 random bytes encoded with unpadded URL-safe Base64" in {
      val secret = service.generate()

      Base64.getUrlDecoder.decode(secret).length shouldEqual 32
      secret.contains("=") shouldEqual false
      secret.contains("+") shouldEqual false
      secret.contains("/") shouldEqual false
    }
  }

  "InvitationSecretService.hash" should {
    "produce a 32 byte SHA-256 digest" in {
      service.hash("invitation-secret").length shouldEqual 32
    }
  }

  "InvitationSecretService.matches" should {
    "match the secret used to create the expected hash" in {
      val secret = "invitation-secret"
      service.matches(secret, service.hash(secret)) shouldEqual true
    }

    "reject a different secret" in {
      service.matches("wrong-secret", service.hash("invitation-secret")) shouldEqual false
    }
  }
}
