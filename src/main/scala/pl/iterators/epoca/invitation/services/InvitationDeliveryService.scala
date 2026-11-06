package pl.iterators.epoca.invitation.services

import cats.effect.IO
import cats.effect.std.Supervisor
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.InvitationOnboardingRepository
import pl.iterators.epoca.invitation.services.InvitationManagementService._
import pl.iterators.epoca.mail.domain.{InvitationMailResult, MailDomain}
import pl.iterators.epoca.mail.emails.UserOrganizationInvitation
import pl.iterators.epoca.mail.services.InvitationMailSender
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Clock
import scala.concurrent.duration._

class InvitationDeliveryService(
  transactor: PostgresIOTransactor,
  repository: InvitationOnboardingRepository,
  organizations: OrganizationRepository,
  sender: InvitationMailSender,
  supervisor: Supervisor[IO],
  clock: Clock) {

  /** Supervise before evaluating the command: HTTP cancellation cannot interrupt commit-to-dispatch. Only registration is masked; application
    * shutdown can cancel database/provider work. Callers receive the committed result as soon as mail is scheduled, never wait for the provider.
    */
  def afterCommit(command: => IO[Result]): IO[Result] = IO.uncancelable { poll =>
    supervisor
      .supervise(IO.defer(command).flatTap {
        case Ok(_, Some(delivery)) => supervisor.supervise(deliver(delivery)).void
        case _                     => IO.unit
      })
      .flatMap(fiber => poll(fiber.joinWithNever))
  }

  private[invitation] def deliver(delivery: Delivery): IO[Unit] = {
    val operation = transactor.execute(repository.find(delivery.invitationId)).flatMap {
      case Some(record) if eligible(record, delivery) =>
        transactor.execute(repository.attempts(delivery.invitationId)).flatMap { attempts =>
          if (!attempts.exists(a => a.id == delivery.attemptId && a.generation == delivery.generation && a.finishedAt.isEmpty)) IO.unit
          else
            transactor.execute(organizations.find(OrganizationFilters(id = Some(record.invitation.organizationId)))).flatMap {
              case Some(org) =>
                val event = UserOrganizationInvitation.Event(
                  MailDomain.MailAddress(record.canonicalEmail.get),
                  org.name,
                  delivery.secret,
                  record.invitation.expireAt.get,
                  record.locale.getOrElse("en")
                )
                sender.send(event).attempt.timeoutTo(30.seconds, IO.pure(Left(ProviderDeadline))).flatMap {
                  case Left(ProviderDeadline)                       => IO.unit
                  case Left(_)                                      => finish(delivery, DeliveryResult.Failed, Some("MAIL_EXCEPTION"))
                  case Right(InvitationMailResult.Sent)             => finish(delivery, DeliveryResult.Succeeded, None)
                  case Right(InvitationMailResult.Skipped)          => finish(delivery, DeliveryResult.Failed, Some("MAIL_SKIPPED"))
                  case Right(InvitationMailResult.TemplateNotFound) => finish(delivery, DeliveryResult.Failed, Some("MAIL_TEMPLATE_NOT_FOUND"))
                  case Right(InvitationMailResult.Error)            => finish(delivery, DeliveryResult.Failed, Some("MAIL_ERROR"))
                }
              case None => IO.unit
            }
        }
      case _ => IO.unit
    }
    // Persistence failures also leave Started. Never hand a throwable containing mail data to a global fiber logger.
    operation.handleErrorWith(_ => IO.unit)
  }

  private def eligible(record: Record, delivery: Delivery): Boolean = {
    val invitation = record.invitation
    record.protocolVersion == 2 && record.generation == delivery.generation &&
    record.tokenHash.contains(Digest(new InvitationSecretService().hash(delivery.secret))) && record.canonicalEmail.isDefined &&
    invitation.expireAt.exists(
      _.isAfter(clock.instant())
    ) && invitation.acceptAt.isEmpty && invitation.rejectAt.isEmpty && invitation.archivedAt.isEmpty
  }
  private def finish(
    delivery: Delivery,
    result: DeliveryResult,
    code: Option[String]
  ): IO[Unit] =
    transactor
      .executeTransactionally(repository.finishAttempt(delivery.attemptId, delivery.invitationId, delivery.generation, clock.instant(), result, code))
      .void

  private case object ProviderDeadline extends RuntimeException
}
