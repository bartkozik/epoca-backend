package pl.iterators.epoca.mail.services

import org.mockito.invocation.InvocationOnMock
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import pl.iterators.epoca.mail.domain.MailDomain
import pl.iterators.epoca.mail.domain.MailDomain._
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository.SendgridMailEventFilters
import pl.iterators.epoca.mail.repositories.{MailHistoryRepository, SendgridMailEventRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

import java.time.Instant

class SendgridMailSyncServiceSpec extends ServiceSpec with MockitoSugar with ArgumentMatchersSugar {

  val sendgridMailEventGenerator: AllGenerators[SendgridMailEvent] = allGenerators[SendgridMailEvent]
  val mailAddressGenerator: AllGenerators[MailDomain.MailAddress]  = allGenerators[MailAddress]

  trait TestCase extends BaseScope {
    lazy val mailHistoryPersister: MailHistoryRepository = vmock[MailHistoryRepository]
    lazy val eventPersister: SendgridMailEventRepository = vmock[SendgridMailEventRepository]
    lazy val sendgridMailerConfig: SendgridMailer.Config = mock[SendgridMailer.Config]
    when(sendgridMailerConfig.envName).thenReturn("spec")

    val email1                            = mailAddressGenerator.normal.generate
    val email2                            = mailAddressGenerator.normal.generate
    lazy val sut: SendgridMailSyncService = wire[SendgridMailSyncService]
  }

  "synchronize" should {
    "updates all timestamps if event == clicked" in new TestCase {
      val event   = sendgridMailEventGenerator.minimal.generate.copy(event = Some("click"), timestamp = Some(1558328))
      val clicked = event.timestamp.map(Instant.ofEpochSecond)
      when(eventPersister.list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec"))))
        .thenReturn(Seq(event).asDBIO)

      when(mailHistoryPersister.updateWebhookTimestamps(any, any, any, any)).thenReturn(1.asDBIO)

      when(eventPersister.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[SendgridMailEvent]).asDBIO
      }

      sut.synchronize().asserting(_ shouldEqual ())

      verify(eventPersister).list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec")))

      verify(mailHistoryPersister).updateWebhookTimestamps(event.mailUuid, None, None, clicked)
      verify(mailHistoryPersister, times(0)).list(any)
      verify(mailHistoryPersister, times(0)).update(any)

      verify(eventPersister).update(event.copy(processed = Some(true)))
    }
    "updates delivered timestamp if event == delivered" in new TestCase {
      val event     = sendgridMailEventGenerator.minimal.generate.copy(event = Some("delivered"), timestamp = Some(1558328))
      val delivered = event.timestamp.map(Instant.ofEpochSecond)

      when(eventPersister.list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec"))))
        .thenReturn(Seq(event).asDBIO)

      when(mailHistoryPersister.updateWebhookTimestamps(any, any, any, any)).thenReturn(0.asDBIO)

      when(eventPersister.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[SendgridMailEvent]).asDBIO
      }

      sut.synchronize().asserting(_ shouldEqual ())

      verify(eventPersister).list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec")))

      verify(mailHistoryPersister).updateWebhookTimestamps(event.mailUuid, delivered, None, None)
      verify(mailHistoryPersister, times(0)).list(any)
      verify(mailHistoryPersister, times(0)).update(any)

      verify(eventPersister).update(event.copy(processed = Some(true)))
    }

    "updates delivered and opened timestamps if event == opened" in new TestCase {
      val event  = sendgridMailEventGenerator.minimal.generate.copy(event = Some("open"), timestamp = Some(1558328))
      val opened = event.timestamp.map(Instant.ofEpochSecond)

      when(eventPersister.list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec"))))
        .thenReturn(Seq(event).asDBIO)

      when(mailHistoryPersister.updateWebhookTimestamps(any, any, any, any)).thenReturn(1.asDBIO)

      when(eventPersister.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[SendgridMailEvent]).asDBIO
      }

      sut.synchronize().asserting(_ shouldEqual ())

      verify(eventPersister).list(SendgridMailEventFilters(processed = Some(false), environment = Some("spec")))

      verify(mailHistoryPersister).updateWebhookTimestamps(event.mailUuid, None, opened, None)
      verify(mailHistoryPersister, times(0)).list(any)
      verify(mailHistoryPersister, times(0)).update(any)

      verify(eventPersister).update(event.copy(processed = Some(true)))
    }
  }
}
