package pl.iterators.epoca.saml.services

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all._
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.repositories.UserAvatarSourceRepository
import pl.iterators.epoca.user.services.{AvatarSyncAdmission, AvatarSyncService}
import pl.iterators.epoca.utils.ServiceSpec

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class WorkspaceAvatarSyncServiceSpec extends ServiceSpec {
  "Workspace background dispatch" should {
    "release admission on synchronous repository construction failures and retain the failure cooldown" in new BaseScope {
      val avatars = mock[AvatarSyncService]
      when(avatars.admission).thenReturn(new AvatarSyncAdmission(4, 4096, 5.minutes))
      val sources  = mock[UserAvatarSourceRepository]
      val provider = mock[WorkspaceDirectoryPhotoProvider]
      val proof    = WorkspacePhotoProof(OrganizationId(UUID.randomUUID()), 1L, "issuer", "subject", "member@example.com")
      val calls    = new AtomicInteger()
      val users    = List.fill(6)(allGenerators[UserWithIdentities].normal.generate.copy(avatar = None))
      when(provider.configured(proof)).thenReturn(true)
      when(sources.find(any)).thenAnswer { (_: InvocationOnMock) =>
        val _ = calls.incrementAndGet()
        throw new RuntimeException("repository unavailable")
      }
      val service = new WorkspaceAvatarSyncService(provider, avatars, sources, transactor)
      val result = TestControl.executeEmbed {
        users.traverse_(u => service.schedule(u, proof) >> IO.sleep(1.second)) >>
          service.schedule(users.head, proof) >> IO.sleep(1.second) >> IO(calls.get())
      }
      result.asserting(_ mustEqual 6)
      verify(provider, never).fetch(any)
    }
    "perform no repository or provider IO for an unconfigured organization" in new BaseScope {
      val avatars = mock[AvatarSyncService]
      when(avatars.admission).thenReturn(new AvatarSyncAdmission(4, 4096, 5.minutes))
      val sources  = mock[UserAvatarSourceRepository]
      val provider = mock[WorkspaceDirectoryPhotoProvider]
      val proof    = WorkspacePhotoProof(OrganizationId(UUID.randomUUID()), 1L, "issuer", "subject", "member@example.com")
      val user     = allGenerators[UserWithIdentities].normal.generate
      when(provider.configured(proof)).thenReturn(false)
      new WorkspaceAvatarSyncService(provider, avatars, sources, transactor).schedule(user, proof).asserting(_ mustEqual ())
      verifyNoInteractions(sources)
      verify(provider, never).fetch(any)
    }
  }
}
