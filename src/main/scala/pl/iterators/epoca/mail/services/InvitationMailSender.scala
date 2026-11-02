package pl.iterators.epoca.mail.services

import cats.effect.IO
import pl.iterators.epoca.mail.domain.InvitationMailResult
import pl.iterators.epoca.mail.emails.UserOrganizationInvitation

trait InvitationMailSender {
  def send(event: UserOrganizationInvitation.Event): IO[InvitationMailResult]
}
