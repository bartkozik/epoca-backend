package pl.iterators.epoca.invitation.domain

import org.specs2.mutable.SpecificationLike

import java.time.Instant

class InvitationPolicySpec extends SpecificationLike {
  private val now = Instant.parse("2026-10-01T10:00:00Z")

  "InvitationPolicy.isAcceptable" should {
    "reject an invitation at its expiration instant" in {
      InvitationPolicy.isAcceptable(now, Some(now), None, None, None) shouldEqual false
    }

    "accept an invitation before its expiration instant" in {
      InvitationPolicy.isAcceptable(now, Some(now.plusSeconds(1)), None, None, None) shouldEqual true
    }

    "allow legacy invitations without an expiration" in {
      InvitationPolicy.isAcceptable(now, None, None, None, None) shouldEqual true
    }

    "reject accepted invitations" in {
      InvitationPolicy.isAcceptable(now, None, Some(now), None, None) shouldEqual false
    }

    "reject rejected invitations" in {
      InvitationPolicy.isAcceptable(now, None, None, Some(now), None) shouldEqual false
    }

    "reject archived invitations" in {
      InvitationPolicy.isAcceptable(now, None, None, None, Some(now)) shouldEqual false
    }
  }

  "InvitationPolicy.deliveryUnknown" should {
    "remain false before the 120 second threshold" in {
      InvitationPolicy.deliveryUnknown(now, now, None) shouldEqual false
      InvitationPolicy.deliveryUnknown(now.plusSeconds(119), now, None) shouldEqual false
    }

    "be true at the 120 second threshold" in {
      InvitationPolicy.deliveryUnknown(now.plusSeconds(120), now, None) shouldEqual true
    }

    "remain false after a delivery has finished" in {
      InvitationPolicy.deliveryUnknown(now.plusSeconds(120), now, Some(now.plusSeconds(30))) shouldEqual false
    }
  }

  "InvitationPolicy.nextAllowedSend" should {
    "allow the next send after 60 seconds" in {
      InvitationPolicy.nextAllowedSend(now) shouldEqual now.plusSeconds(60)
    }
  }
}
