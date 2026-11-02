package pl.iterators.epoca.saml.services

import cats.effect.IO
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlDomain.{SamlOneTimeToken, SamlRequestId}
import pl.iterators.epoca.saml.repositories.{SamlConfigRepository, SamlRequestRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Clock

class SamlAuthorizeService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  samlConfigRepository: SamlConfigRepository,
  samlRequestRepository: SamlRequestRepository,
  samlUtilsService: SamlUtilsService,
  samlInvitationService: SamlInvitationService,
  clock: Clock) {
  import SamlAuthorizeService._
  import SamlAuthorizeResult._

  def authorize(response: String, organizationSlug: OrganizationSlug): IO[SamlAuthorizeResult] =
    samlUtilsService
      .extractRequestId(response)
      .flatMap {
        case None => IO.pure(NoInResponseTo)
        case Some(id) =>
          transactor.execute(samlRequestRepository.find(id)).flatMap {
            case None                                                     => IO.pure(RequestNotFound(id))
            case Some(request) if request.used || request.token.isDefined => IO.pure(RequestAlreadyUsed)
            case Some(request) =>
              request.organizationId match {
                case None => IO.pure(ResponseInvalid)
                case Some(organizationId) =>
                  transactor.execute(organizationRepository.find(OrganizationFilters(id = Some(organizationId)))).flatMap {
                    case None                                                        => IO.pure(OrganizationNotFound)
                    case Some(organization) if organization.slug != organizationSlug => IO.pure(ResponseInvalid)
                    case Some(organization) =>
                      transactor.execute(samlConfigRepository.find(organizationId)).flatMap {
                        case None                              => IO.pure(SamlConfigNotFound)
                        case Some(config) if !config.isEnabled => IO.pure(ResponseInvalid)
                        case Some(config) if request.createdAt.isAfter(clock.instant()) || !request.isValid(clock.instant(), config.validFor) =>
                          IO.pure(RequestExpired)
                        case Some(config) =>
                          samlUtilsService.parseResponse(response, config, organization).flatMap {
                            case Some(parsed) =>
                              IO(samlUtilsService.isValid(parsed, id)).flatMap {
                                case false => IO.pure(ResponseInvalid)
                                case true =>
                                  samlUtilsService.getAuthContext(parsed).flatMap {
                                    case None => IO.pure(ResponseAttributesMissing)
                                    case Some(context) =>
                                      transactor.executeTransactionally(samlInvitationService.authorizeAction(request, config, context)).map {
                                        case Some(saved) if saved.token.isDefined => Authorized(saved.token.get)
                                        case _                                    => ResponseInvalid
                                      }
                                  }
                              }
                            case None => IO.pure(ResponseInvalid)
                          }
                      }
                  }
              }
          }
      }
      .handleError(_ => ResponseInvalid)
}
object SamlAuthorizeService {
  sealed trait SamlAuthorizeResult
  object SamlAuthorizeResult {
    case class Authorized(oneTimeToken: SamlOneTimeToken) extends SamlAuthorizeResult {
      override def toString: String = "SamlAuthorized(<redacted>)"
    }
    case class RequestNotFound(id: SamlRequestId) extends SamlAuthorizeResult
    case object OrganizationNotFound              extends SamlAuthorizeResult
    case object SamlConfigNotFound                extends SamlAuthorizeResult
    case object NoInResponseTo                    extends SamlAuthorizeResult
    case object ResponseInvalid                   extends SamlAuthorizeResult
    case object RequestAlreadyUsed                extends SamlAuthorizeResult
    case object RequestExpired                    extends SamlAuthorizeResult
    case object ResponseAttributesMissing         extends SamlAuthorizeResult
  }
}
