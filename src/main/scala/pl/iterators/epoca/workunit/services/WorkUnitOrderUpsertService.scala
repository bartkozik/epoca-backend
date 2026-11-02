package pl.iterators.epoca.workunit.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitId}
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import pl.iterators.epoca.workunit.repositories.WorkUnitOrderRepository
import pl.iterators.epoca.workunit.services.WorkUnitOrderUpsertService.{WorkUnitOrderUpsertRequest, WorkUnitOrderUpsertResult}
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class WorkUnitOrderUpsertService(
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitOrderRepository: WorkUnitOrderRepository,
  transactor: PostgresIOTransactor
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitOrderUpsertResult](ioRuntime)
    with LoggingSupport {
  import WorkUnitOrderUpsertService.WorkUnitOrderUpsertResult._
  def upsert(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: WorkUnitOrderUpsertRequest
  ): IO[WorkUnitOrderUpsertResult] =
    transactor.execute {
      (for {
        organization <- findOrganization(organizationSlug)
        _            <- checkAccessToOrganization(authContext, organization)
        order        <- prepareOrder(organization, authContext, request)
        _            <- upsertOrder(order)
      } yield Ok(order)).run
    }

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
      .valueOrF {
        Logger[DBIO]
          .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
          .as(NoAccessToOrganization)
      }
      .void

  private def prepareOrder(
    organization: Organization,
    authContext: AuthContext,
    request: WorkUnitOrderUpsertRequest
  ): StepDBIO[WorkUnitOrder] =
    Sealed.liftF(WorkUnitOrder(organization.id, authContext.id, request.date, request.workUnitIds))

  private def upsertOrder(order: WorkUnitOrder): StepDBIO[WorkUnitOrder] =
    workUnitOrderRepository.upsert(order).seal

}

object WorkUnitOrderUpsertService {
  case class WorkUnitOrderUpsertRequest(date: WorkUnitDate, workUnitIds: Seq[WorkUnitId])

  sealed trait WorkUnitOrderUpsertResult
  object WorkUnitOrderUpsertResult {
    case class Ok(order: WorkUnitOrder) extends WorkUnitOrderUpsertResult
    case object OrganizationNotFound    extends WorkUnitOrderUpsertResult
    case object NoAccessToOrganization  extends WorkUnitOrderUpsertResult
  }
}
