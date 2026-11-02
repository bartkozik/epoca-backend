package pl.iterators.epoca.mail.services

import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.mail.domain.MailDomain.SendgridMailEvent
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec

class SendgridMailEventCreateServiceSpec extends ServiceSpec {

  val sendgridMailEventGenerator: AllGenerators[SendgridMailEvent] = allGenerators[SendgridMailEvent]

  trait TestCase extends BaseScope {
    val sendgridMailEventRepository: SendgridMailEventRepository = vmock[SendgridMailEventRepository]

    val sendgridMailEventCreateService: SendgridMailEventCreateService = wire[SendgridMailEventCreateService]
  }

  "create" should {
    "call persister" in new TestCase {
      val events =
        List(sendgridMailEventGenerator.normal.generate, sendgridMailEventGenerator.normal.generate, sendgridMailEventGenerator.normal.generate)

      when(sendgridMailEventRepository.create(any)).thenAnswer { (invocation: InvocationOnMock) =>
        invocation.getArguments()(0).asInstanceOf[Seq[SendgridMailEvent]].asDBIO
      }

      sendgridMailEventCreateService.create(events).asserting(_ shouldEqual events)

      verify(sendgridMailEventRepository).create(events)
    }
  }

}
