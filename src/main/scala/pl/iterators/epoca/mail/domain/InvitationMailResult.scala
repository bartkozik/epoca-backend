package pl.iterators.epoca.mail.domain

sealed trait InvitationMailResult
object InvitationMailResult {
  case object Sent             extends InvitationMailResult
  case object Skipped          extends InvitationMailResult
  case object TemplateNotFound extends InvitationMailResult
  case object Error            extends InvitationMailResult
}
