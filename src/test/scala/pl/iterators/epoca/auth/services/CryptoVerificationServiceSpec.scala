package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.services.CryptoVerificationService.CryptoVerificationResult
import pl.iterators.epoca.utils.ServiceSpec

class CryptoVerificationServiceSpec extends ServiceSpec {

  trait TestCase extends BaseScope {
    val service: CryptoVerificationService = wire[CryptoVerificationService]
  }
  val wrongKey: Base64PublicKey = Base64PublicKey("MIIBITANBgkqhkiG")

  val correctKey: Base64PublicKey = Base64PublicKey(
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuZJjxb5fJOVCUJ7w0e/4AKK3saZekW18qr28bBh+fekBk6QAanu10xM+251AHa2J80Bb53Qgnc2eCoESw+JLO4NDuptsDaASjBt/EiKeVgqHr7ZIGCVE95TqffTJj2GnrlaW+J2tandUi+fKKQlmjM2cI1e2KVcyfzUS9wI7lMgg8a+UeDQltQadkgFpYtkgPxB1/qFhsQSP5t2HI9oyz3+NvZdMfMkmxLvhU4D6Q9SrfFO8A1U7QXEDJ8odggaDmnhLR3VVU5PSVTHQfGWmI81vjX9KCSz+7h74sjLWBbWO11XROtcQzXUrpWchvan6AAlbdD9QWBlhBnQr5QsLIwIDAQAB"
  )

  val wrongSignature: CryptoSignature = CryptoSignature("qFHlQCFrvl3MgvVHNRIzygmBB6cYGd2LdwVBNWSLOH5ORGIlsrsGDsi2yNGgSQD")

  val correctSignature: CryptoSignature = CryptoSignature(
    "NZGlS/CZgNYb1Gjr+yM/5Fi2NNyk8UbSxMt7ns1g5QXQKXVcifGxkgSyqDBg5F+f0v+rm84loeiGdYOSNCVcXvkT0wuXd1FUEzceyb2OyP+hxMoyLSGoPrcIBTG2bM2wlcqdtzg8cFWAUCi9WvB5TToVDgyzrWeTycWIlBetvkAquE5xb4OgOLjrVvJTTP0O0//hubXs+rC56gYFCR+o2pT6prPoLWjiXhJhfPIIa/PV3thnQ5lIgTyRO9GiHaVmpJjNzf7l1iU7cOb1/dR0dif2X+pfwSR33iy5HaYb0DQCNfjUJQtisKjE2T5ZTKq6PNPpgcmUdYEiNQuaaBN01w=="
  )

  val verificationData: CryptoVerificationData = CryptoVerificationData("some message")

  "verify" should {
    "return UnableToCreatePublicKey for wrong public key data" in new TestCase {
      service
        .verify(SignatureAlgorithm.SHA256withRSA, wrongKey, correctSignature, verificationData)
        .asserting(_ shouldEqual CryptoVerificationResult.UnableToCreatePublicKey)
    }
    "return NotVerified for correct public key data and wrong verification data" in new TestCase {
      service
        .verify(SignatureAlgorithm.SHA256withRSA, correctKey, wrongSignature, verificationData)
        .asserting(_ shouldEqual CryptoVerificationResult.NotVerified)
    }
    "return Verified for correct key data and correct verification data" in new TestCase {
      service
        .verify(SignatureAlgorithm.SHA256withRSA, correctKey, correctSignature, verificationData)
        .asserting(_ shouldEqual CryptoVerificationResult.Verified)
    }
  }

}
