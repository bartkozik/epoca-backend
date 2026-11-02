package pl.iterators.epoca.organization.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationUpdateService.{OrganizationUpdateRequest, OrganizationUpdateResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class OrganizationUpdateService(transactor: PostgresIOTransactor, organizationRepository: OrganizationRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationUpdateResult](ioRuntime)
    with LoggingSupport {

  def update(
    authContext: AuthContext,
    slug: OrganizationSlug,
    request: OrganizationUpdateRequest
  ): IO[OrganizationUpdateResult] =
    transactor.execute {
      (for {
        _            <- Logger[DBIO].info(s"OrganizationUpdateService: auth.id=${authContext.id}, organization=$slug").seal
        organization <- findOrganization(slug)
        _            <- checkPermission(authContext, organization)
        toUpdate     <- prepareOrganization(authContext, organization, request)
        result       <- updateOrganization(toUpdate)
      } yield OrganizationUpdateResult.Updated(result)).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"OrganizationUpdateService: organization $slug does not exist").as(OrganizationUpdateResult.OrganizationNotFound)
      }

  private def checkPermission(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(
        _ => authContext.id == organization.createdBy || authContext.isSuperAdmin,
        Logger[DBIO]
          .warning(s"OrganizationUpdateService: user ${authContext.id} has no permission")
          .as(OrganizationUpdateResult.OperationNotPermitted)
      )

  private def prepareOrganization(
    authContext: AuthContext,
    organization: Organization,
    request: OrganizationUpdateRequest
  ): StepDBIO[Organization] =
    Clock[DBIO].instantNow.seal.map { now =>
      organization
        .copy(name = request.name, modifiedAt = OrganizationModifiedAt(now), modifiedBy = authContext.id)
    }

  private def updateOrganization(organization: Organization): StepDBIO[Organization] =
    (Logger[DBIO].info(s"OrganizationUpdateService: successfully deleted organization ${organization.id}") *>
      organizationRepository
        .update(organization))
      .valueOr(OrganizationUpdateResult.OrganizationNotFound)
}

object OrganizationUpdateService {
  final case class OrganizationUpdateRequest(name: OrganizationName)

  sealed trait OrganizationUpdateResult
  object OrganizationUpdateResult {
    final case class Updated(organization: Organization) extends OrganizationUpdateResult
    final case object OperationNotPermitted              extends OrganizationUpdateResult
    final case object OrganizationNotFound               extends OrganizationUpdateResult
  }

}
