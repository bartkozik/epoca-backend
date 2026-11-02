package pl.iterators.epoca.saml.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.repositories.SamlConfigRepository
import pl.iterators.epoca.saml.services.SamlConfigUpsertService.SamlConfigUpsertResult._
import pl.iterators.epoca.saml.services.SamlConfigUpsertService.{SamlConfigUpsertRequest, SamlConfigUpsertResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

case class SamlConfigUpsertService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  samlConfigRepository: SamlConfigRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[SamlConfigUpsertResult](ioRuntime)
    with LoggingSupport {

  def upsert(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: SamlConfigUpsertRequest
  ): IO[SamlConfigUpsertResult] = {
    val query = for {
      organization <- findOrganization(organizationSlug)
      orgUser      <- checkAccessToOrganization(authContext, organization)
      _            <- checkPermissionLevel(authContext, orgUser)
      samlConfig   <- prepareSamlConfig(request, organization)
      samlConfig   <- upsertSamlConfig(samlConfig)
    } yield Created(samlConfig)
    transactor.executeTransactionally(query.run)
  }

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) None.liftSealed[DBIO, SamlConfigUpsertResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id), forUpdate = true))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)
  private def checkPermissionLevel(authContext: AuthContext, orgUserOpt: Option[OrganizationUser]): StepDBIO[Unit] =
    orgUserOpt match {
      case None => ().liftSealed[DBIO, SamlConfigUpsertResult]
      case Some(orgUser) =>
        orgUser.roles
          .intersect(Seq(OrganizationUserRole.Owner))
          .liftSealed[DBIO, SamlConfigUpsertResult]
          .ensureF(
            _.nonEmpty,
            Logger[DBIO]
              .warning(
                s"User ${authContext.id} tried to edit SAML config for organization ${orgUser.organizationId}. Failed due to insufficient permissions."
              )
              .as(PermissionsAreTooLow)
          )
          .map(_ => ())
    }

  private def prepareSamlConfig(request: SamlConfigUpsertRequest, organization: Organization): StepDBIO[SamlConfig] =
    (Clock[DBIO].instantNow.map { now =>
      request
        .into[SamlConfig]
        .withFieldConst(_.createdAt, SamlCreatedAt(now))
        .withFieldConst(_.organizationId, organization.id)
        .transform
    }: DBIO[SamlConfig]).seal

  private def upsertSamlConfig(samlConfig: SamlConfig): StepDBIO[SamlConfig] =
    (Logger[DBIO].info(s"successfully created saml config for organization ${samlConfig.organizationId}") *> samlConfigRepository.upsert(
      samlConfig
    )).seal

}

object SamlConfigUpsertService {
  final case class SamlConfigUpsertRequest(
    idpCert: SamlIdpCert,
    idpEntityId: SamlIdpEntityId,
    idpSsoUrl: SamlIdpSsoUrl,
    langCode: SamlLangCode,
    validFor: SamlValidityDuration,
    allowAccountInit: SamlAllowAccountInitialization,
    isEnabled: SamlIsEnabled)

  sealed trait SamlConfigUpsertResult
  object SamlConfigUpsertResult {
    final case class Created(samlConfig: SamlConfig) extends SamlConfigUpsertResult
    final case object OrganizationNotFound           extends SamlConfigUpsertResult
    final case object NoAccessToOrganization         extends SamlConfigUpsertResult
    final case object PermissionsAreTooLow           extends SamlConfigUpsertResult
  }
}
