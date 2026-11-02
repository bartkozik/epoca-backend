package pl.iterators.epoca.saml.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigXmlService.SamlConfigXmlFindResult
import pl.iterators.epoca.saml.services.SamlConfigXmlService.SamlConfigXmlFindResult._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class SamlConfigXmlService(
  transactor: PostgresIOTransactor,
  samlUtilsService: SamlUtilsService,
  samlConfigRepository: SamlConfigRepository,
  organizationRepository: OrganizationRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[SamlConfigXmlFindResult](ioRuntime)
    with LoggingSupport {
  def find(organizationSlug: OrganizationSlug): IO[SamlConfigXmlFindResult] = {
    val query = for {
      organization <- findOrganization(organizationSlug)
      samlConfig   <- findSamlConfig(organization)
      metadata     <- samlUtilsService.settings(samlConfig, organization).seal
    } yield Ok(metadata.getSPMetadata)
    query.run
  }
  private def findOrganization(organizationSlug: OrganizationSlug): StepIO[Organization] =
    transactor
      .execute(
        organizationRepository
          .find(OrganizationFilters(slug = Some(organizationSlug)))
      )
      .valueOrF {
        Logger[IO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def findSamlConfig(organization: Organization): StepIO[SamlConfig] = {
    val query: DBIO[Option[SamlConfig]] = samlConfigRepository.find(organization.id)
    transactor
      .execute(query)
      .valueOrF {
        Logger[IO].error(s"organization ${organization.id} does not have saml config").as(SamlConfigNotFound)
      }
      .ensureF(_.isEnabled, Logger[IO].info(s"organization ${organization.id} has saml config - but it's disabled").as(SamlDisabled))
  }
}

object SamlConfigXmlService {
  sealed trait SamlConfigXmlFindResult
  object SamlConfigXmlFindResult {
    case class Ok(xml: String)       extends SamlConfigXmlFindResult
    case object OrganizationNotFound extends SamlConfigXmlFindResult
    case object SamlConfigNotFound   extends SamlConfigXmlFindResult
    case object SamlDisabled         extends SamlConfigXmlFindResult
  }
}
