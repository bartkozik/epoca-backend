package pl.iterators.epoca.invitation.services

import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO}
import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.InvitationOnboardingRepository
import pl.iterators.epoca.invitation.services.InvitationManagementService._
import pl.iterators.epoca.mail.domain.InvitationMailResult
import pl.iterators.epoca.mail.emails.UserOrganizationInvitation
import pl.iterators.epoca.mail.services.InvitationMailSender
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.{DBIO, SuccessAction}

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration._

class InvitationDeliveryServiceSpec extends ServiceSpec {
  private val now    = Instant.parse("2026-10-02T10:00:00Z")
  private val secret = "a" * 43
  private val org    = allGenerators[Organization].normal.generate.copy(archivedAt = None)
  private val invitation = allGenerators[Invitation].normal.generate.copy(
    organizationId = org.id,
    expireAt = Some(InvitationExpireAt(now.plusSeconds(604800))),
    acceptAt = None,
    rejectAt = None,
    archivedAt = None
  )
  private val delivery = Delivery(invitation.id, InvitationDeliveryAttemptId(UUID.randomUUID()), 1, secret)
  private val record = Record(
    invitation,
    2,
    Some("Anna"),
    Some("Test"),
    0,
    1,
    Some(Digest(new InvitationSecretService().hash(secret))),
    None,
    Some("pl"),
    Some("anna@example.test")
  )

  private class Harness(outcome: IO[InvitationMailResult], current: Record = record) {
    val repository: InvitationOnboardingRepository      = mock[InvitationOnboardingRepository]
    val organizations: OrganizationRepository           = mock[OrganizationRepository]
    var mails: Vector[UserOrganizationInvitation.Event] = Vector.empty[UserOrganizationInvitation.Event]
    var finishes: Vector[(InvitationDeliveryAttemptId, InvitationId, Long, DeliveryResult, Option[String])] =
      Vector.empty[(InvitationDeliveryAttemptId, InvitationId, Long, DeliveryResult, Option[String])]
    var transactional = 0
    val transactor: PostgresIOTransactor = new PostgresIOTransactor(null)(ioRuntime) {
      override def execute[A](action: => DBIO[A]): IO[A] = IO(action match {
        case SuccessAction(value) => value; case _ => throw new IllegalArgumentException("Unsupported test database action")
      })
      override def executeTransactionally[A](action: => DBIO[A]): IO[A] = IO(transactional += 1) *> execute(action)
    }
    when(repository.find(invitation.id)).thenReturn(DBIO.successful(Some(current)))
    when(repository.attempts(invitation.id))
      .thenReturn(DBIO.successful(Vector(DeliveryAttempt(delivery.attemptId, invitation.id, 1, now, None, None, None))))
    when(organizations.find(any)).thenReturn(DBIO.successful(Some(org)))
    when(repository.finishAttempt(any, any, any, any, any, any)).thenAnswer { call: _root_.org.mockito.invocation.InvocationOnMock =>
      finishes :+= ((
        call.getArgument[InvitationDeliveryAttemptId](0),
        call.getArgument[InvitationId](1),
        call.getArgument[Long](2),
        call.getArgument[DeliveryResult](4),
        call.getArgument[Option[String]](5)
      ))
      DBIO.successful(true)
    }
    def service(supervisor: Supervisor[IO]) = new InvitationDeliveryService(
      transactor,
      repository,
      organizations,
      new InvitationMailSender { def send(event: UserOrganizationInvitation.Event): IO[InvitationMailResult] = IO(mails :+= event) *> outcome },
      supervisor,
      Clock.fixed(now, ZoneOffset.UTC)
    )
  }

  "invitation delivery" should {
    Seq(
      (InvitationMailResult.Sent, DeliveryResult.Succeeded, None),
      (InvitationMailResult.Skipped, DeliveryResult.Failed, Some("MAIL_SKIPPED")),
      (InvitationMailResult.TemplateNotFound, DeliveryResult.Failed, Some("MAIL_TEMPLATE_NOT_FOUND")),
      (InvitationMailResult.Error, DeliveryResult.Failed, Some("MAIL_ERROR"))
    ).foreach { case (result, stored, code) =>
      s"record $result without leaking mail data" in {
        val h = new Harness(IO.pure(result))
        TestControl.executeEmbed(Supervisor[IO](await = false).use(s => h.service(s).deliver(delivery))).map { _ =>
          h.finishes mustEqual Vector((delivery.attemptId, invitation.id, 1L, stored, code))
          h.transactional mustEqual 1
          h.mails.map(_.organizationName) mustEqual Vector(org.name: String)
          h.mails.map(_.email: String) mustEqual Vector("anna@example.test")
        }
      }
    }
    "sanitize provider exceptions" in {
      val h = new Harness(IO.raiseError(new RuntimeException(secret)))
      TestControl.executeEmbed(Supervisor[IO](await = false).use(s => h.service(s).deliver(delivery))).map { _ =>
        h.finishes.map(_._5) mustEqual Vector(Some("MAIL_EXCEPTION"))
      }
    }
    "leave a deadline-expired provider Started for later Unknown recovery" in {
      val h = new Harness(IO.never)
      TestControl.executeEmbed(Supervisor[IO](await = false).use(s => h.service(s).deliver(delivery))).map { _ =>
        h.finishes must beEmpty
        h.mails must haveSize(1)
      }
    }
    "skip an obsolete generation before sending" in {
      val h = new Harness(IO.pure(InvitationMailResult.Sent), record.copy(generation = 2))
      TestControl.executeEmbed(Supervisor[IO](await = false).use(s => h.service(s).deliver(delivery))).map(_ => h.mails must beEmpty)
    }
    "survive request cancellation after commit and stop on application shutdown" in {
      TestControl.executeEmbed(for {
        started  <- Deferred[IO, Unit]
        canceled <- Deferred[IO, Unit]
        h = new Harness((started.complete(()) *> IO.never).onCancel(canceled.complete(()).void))
        _ <- Supervisor[IO](await = false).use { s =>
               val service = h.service(s)
               for {
                 request <- (service.afterCommit(IO.pure(Ok(io.circe.Json.Null, Some(delivery)))) *> IO.never).start
                 _       <- started.get
                 _       <- request.cancel
                 stopped <- canceled.tryGet
                 _       <- IO(stopped must beNone)
               } yield ()
             }
        stopped <- canceled.tryGet
      } yield stopped must beSome(()))
    }
    "complete commit-to-dispatch exactly once when the waiting HTTP request is canceled" in {
      TestControl.executeEmbed(for {
        commitStarted <- Deferred[IO, Unit]
        commitAllowed <- Deferred[IO, Unit]
        mailStarted   <- Deferred[IO, Unit]
        h = new Harness(mailStarted.complete(()).as(InvitationMailResult.Sent))
        _ <- Supervisor[IO](await = false).use { s =>
               for {
                 request <- h.service(s).afterCommit(commitStarted.complete(()) *> commitAllowed.get.as(Ok(io.circe.Json.Null, Some(delivery)))).start
                 _       <- commitStarted.get
                 _       <- request.cancel
                 _       <- IO(h.mails must beEmpty)
                 _       <- commitAllowed.complete(())
                 _       <- mailStarted.get
                 _       <- IO.cede
               } yield ()
             }
      } yield h.mails must haveSize(1))
    }
    "apply the deadline at exactly thirty seconds" in {
      TestControl.executeEmbed(for {
        canceled <- Deferred[IO, Unit]
        h = new Harness(IO.never.onCancel(canceled.complete(()).void))
        _ <- Supervisor[IO](await = false).use { s =>
               for {
                 fiber  <- h.service(s).deliver(delivery).start
                 _      <- IO.sleep(29.seconds)
                 before <- canceled.tryGet
                 _      <- IO(before must beNone)
                 _      <- IO.sleep(1.second)
                 _      <- fiber.joinWithNever
                 after  <- canceled.tryGet
                 _      <- IO(after must beSome(()))
               } yield ()
             }
      } yield h.finishes must beEmpty)
    }
    "never start delivery before the command commits or on idempotent replay" in {
      val h = new Harness(IO.pure(InvitationMailResult.Sent))
      TestControl
        .executeEmbed(Supervisor[IO](await = false).use { s =>
          h.service(s).afterCommit(IO.raiseError(new RuntimeException("rollback"))).attempt *>
            h.service(s).afterCommit(IO.pure(Ok(io.circe.Json.Null, None))) *> IO.cede
        })
        .map(_ => h.mails must beEmpty)
    }
  }
}
