package pl.iterators.epoca.oauth.services

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike

import java.net.InetAddress

class TrustedClientIpServiceSpec extends SpecificationLike {
  private val config = OAuthConfig
    .load(
      ConfigFactory
        .parseString("""
          |login.jwtSecret = "human-key"
          |oauth {
          |  enabled = false
          |  issuer = "https://staging.epoca.example.test/oauth"
          |  audience = "epoca-api"
          |  dummyBcryptHash = "$2a$12$ehEdMkDhUJM6IZKn19mV0uG6MTaHhmGdT5/SdH8QfX4bMKdZ99kQm"
          |  ipRateLimit { capacity = 60, refillPeriod = 60 seconds }
          |  knownClientFailedSecretRateLimit { capacity = 10, refillPeriod = 300 seconds }
          |  authenticationAuditRetention = 90 days
          |  auditCleanupBatchSize = 100
          |  rateLimitCleanupBatchSize = 100
          |  trustedProxyCidrs = ["10.0.0.0/8", "2001:db8:feed::/48"]
          |  signing { keys = [] }
          |}
          |""".stripMargin)
        .resolve()
    )
    .toOption
    .get
  private val sut = new TrustedClientIpService(config.trustedProxyCidrs)

  "trusted client IP resolution" should {
    "ignore forwarding data from an untrusted socket peer" in {
      sut.resolve(InetAddress.getByName("203.0.113.10"), Seq("198.51.100.11", "10.2.3.4")) shouldEqual InetAddress.getByName("203.0.113.10")
    }

    "walk a trusted proxy chain from the socket peer to the nearest untrusted hop" in {
      sut.resolve(InetAddress.getByName("10.9.8.7"), Seq("192.0.2.44", "198.51.100.33", "10.1.2.3")) shouldEqual InetAddress.getByName(
        "198.51.100.33"
      )
    }

    "fail closed to the socket peer when forwarding data is malformed" in {
      sut.resolve(InetAddress.getByName("10.9.8.7"), Seq("not-an-ip")) shouldEqual InetAddress.getByName("10.9.8.7")
    }
  }
}
