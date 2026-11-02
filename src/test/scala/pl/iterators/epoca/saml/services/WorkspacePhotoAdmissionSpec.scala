package pl.iterators.epoca.saml.services

import org.specs2.mutable.Specification
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.services.AvatarSyncAdmission

import java.util.UUID
import scala.concurrent.duration._

class WorkspacePhotoAdmissionSpec extends Specification {
  "Workspace photo admission" should {
    "drop excess work immediately and retain cooldown after completion or failure" in {
      val gate = new AvatarSyncAdmission(1, 2, 1.minute)
      val a    = UserId(UUID.randomUUID())
      val b    = UserId(UUID.randomUUID())
      gate.acquire(a, 0L) must beTrue
      gate.acquire(b, 0L) must beFalse
      gate.release()
      gate.acquire(a, 1.second.toNanos) must beFalse
      gate.acquire(b, 1.second.toNanos) must beTrue
      gate.release()
      gate.acquire(a, 61.seconds.toNanos) must beTrue
    }
    "bound cooldown memory without evicting unexpired users or queueing new jobs" in {
      val gate = new AvatarSyncAdmission(4, 1, 1.minute)
      val a    = UserId(UUID.randomUUID())
      val b    = UserId(UUID.randomUUID())
      gate.acquire(a, 0L) must beTrue
      gate.release()
      gate.acquire(b, 1.second.toNanos) must beFalse
      gate.acquire(a, 1.second.toNanos) must beFalse
      gate.acquire(b, 61.seconds.toNanos) must beTrue
    }
  }
}
