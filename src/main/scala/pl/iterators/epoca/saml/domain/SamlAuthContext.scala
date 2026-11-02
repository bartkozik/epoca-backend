package pl.iterators.epoca.saml.domain

import pl.iterators.epoca.mail.domain.MailDomain.MailAddress

case class SamlAuthContext(
  id: String,
  attributes: Map[String, List[String]],
  issuer: String = "") {
  val subject: String           = id
  val email: MailAddress        = MailAddress(attributes.get("email").flatMap(_.headOption).getOrElse(id))
  val firstName: Option[String] = attributes.get("firstName").flatMap(_.find(_.trim.nonEmpty))
  val lastName: Option[String]  = attributes.get("lastName").flatMap(_.find(_.trim.nonEmpty))

  def hasFullName: Boolean = firstName.nonEmpty && lastName.nonEmpty
}
