package pl.iterators.epoca.saml.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigGetService.SamlConfigGetResult
import pl.iterators.epoca.saml.services.SamlConfigGetService.SamlConfigGetResult._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class SamlConfigGetService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  samlConfigRepository: SamlConfigRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[SamlConfigGetResult](ioRuntime)
    with LoggingSupport {

  def get(authContext: AuthContext, organizationSlug: OrganizationSlug): IO[SamlConfigGetResult] = {
    val query = for {
      organization <- findOrganization(organizationSlug)
      _            <- checkAccessToOrganization(authContext, organization)
      samlConfig   <- findSamlConfig(organization)
    } yield Ok(samlConfig)
    transactor.execute(query.run)
  }

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) ().liftSealed[DBIO, SamlConfigGetResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .map(_ => ())

  private def findSamlConfig(organization: Organization): StepDBIO[SamlConfig] =
    samlConfigRepository.find(organization.id).valueOrF {
      Logger[DBIO].error(s"Requested organization doesn't have SAML config created").as(SamlConfigNotFound)
    }
}

object SamlConfigGetService {
  sealed trait SamlConfigGetResult
  object SamlConfigGetResult {
    final case class Ok(samlConfig: SamlConfig) extends SamlConfigGetResult
    final case object OrganizationNotFound      extends SamlConfigGetResult
    final case object SamlConfigNotFound        extends SamlConfigGetResult
    final case object NoAccessToOrganization    extends SamlConfigGetResult
  }
}
