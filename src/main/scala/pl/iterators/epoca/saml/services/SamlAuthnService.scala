package pl.iterators.epoca.saml.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.services.InvitationSecretService
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.SamlRequest
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Clock

class SamlAuthnService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  samlConfigRepository: SamlConfigRepository,
  samlUtilsService: SamlUtilsService,
  samlInvitationService: SamlInvitationService,
  secrets: InvitationSecretService,
  clock: Clock,
  browserPolicy: Option[SamlBrowserPolicy]) {
  import SamlAuthnService._
  import SamlAuthnResult._

  def authnRequest(
    organizationSlug: OrganizationSlug,
    auth: Option[AuthContext] = None,
    invitationCookie: Option[String] = None,
    origin: Option[String] = None,
    csrf: Option[String] = None
  ): IO[SamlAuthnResult] =
    if (browserPolicy.isEmpty) IO.pure(Unavailable)
    else
      transactor.execute(organizationRepository.find(OrganizationFilters(slug = Some(organizationSlug)))).flatMap {
        case None => IO.pure(OrganizationNotFound)
        case Some(organization) =>
          transactor.execute(samlConfigRepository.find(organization.id)).flatMap {
            case None                              => IO.pure(SamlConfigNotFound)
            case Some(config) if !config.isEnabled => IO.pure(SamlDisabled)
            case Some(config) =>
              for {
                request <- samlUtilsService.getAuthnRequest(config, organization)
                encoded <- samlUtilsService.buildAndEncodeRequest(request)
                nonce   <- IO(secrets.generate())
                stored = SamlRequest(
                           SamlRequestId(request.getId),
                           None,
                           None,
                           SamlRequestUsed(false),
                           SamlRequestCreatedAt(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
                           organizationId = Some(organization.id),
                           browserNonceHash = Some(secrets.hash(nonce).toVector)
                         )
                saved <- transactor.executeTransactionally(samlInvitationService.startAction(stored, config, auth, invitationCookie, origin, csrf))
              } yield if (saved.isDefined) Ok(SamlAuthnResultData(encoded, config.idpSsoUrl), nonce) else InvalidContext
          }
      }
}
object SamlAuthnService {
  case class SamlAuthnResultData(samlRequest: String, ssoUrl: SamlIdpSsoUrl)
  sealed trait SamlAuthnResult
  object SamlAuthnResult {
    case class Ok(data: SamlAuthnResultData, nonce: String) extends SamlAuthnResult {
      override def toString: String = "SamlAuthn.Ok(<redacted>)"
    }
    case object OrganizationNotFound extends SamlAuthnResult
    case object SamlConfigNotFound   extends SamlAuthnResult
    case object SamlDisabled         extends SamlAuthnResult
    case object InvalidContext       extends SamlAuthnResult
    case object Unavailable          extends SamlAuthnResult
  }
}
