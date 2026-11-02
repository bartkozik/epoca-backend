package pl.iterators.epoca.workunit.services

import cats.Monad
import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitArchivedAt, WorkUnitId, WorkUnitModifiedAt, WorkUnitState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService.WorkUnitDeleteResult
import pl.iterators.epoca.workunit.services.WorkUnitDeleteService.WorkUnitDeleteResult._
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class WorkUnitDeleteService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitRepository: WorkUnitRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId
  ): IO[WorkUnitDeleteResult] =
    transactor.executeTransactionally {
      (for {
        _            <- log(authContext, organizationSlug, workUnitId)
        organization <- findOrganization(organizationSlug)
        orgUser      <- findOrganizationUser(authContext, organization)
        workUnit     <- findWorkUnit(workUnitId, organization)
        _            <- checkAccessToWorkUnit(authContext, workUnit, orgUser)
        softDeleted  <- prepareDeleted(workUnit)
        _            <- softDelete(softDeleted)
      } yield Deleted).run

    }

  private def log(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId
  ): StepDBIO[Unit] =
    Logger[DBIO]
      .info(s"auth.id=${authContext.id}, organization=$organizationSlug, workUnitId=$workUnitId")
      .seal

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound)
      }

  private def findOrganizationUser(authContext: AuthContext, organization: Organization): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) None.liftSealed[DBIO, WorkUnitDeleteResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)

  private def findWorkUnit(workUnitId: WorkUnitId, organization: Organization): StepDBIO[WorkUnit] =
    workUnitRepository
      .find(WorkUnitFilters(id = Some(workUnitId), organizationId = Some(organization.id), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"workUnit $workUnitId does not exist").as(WorkUnitNotFound)
      }

  private def checkAccessToWorkUnit(
    authContext: AuthContext,
    workUnit: WorkUnit,
    orgUser: Option[OrganizationUser]
  ): StepDBIO[Unit] =
    if (authContext.isSuperAdmin || orgUser.exists(_.roles.contains(OrganizationUserRole.Owner))) ().liftSealed[DBIO, WorkUnitDeleteResult]
    else
      Monad[DBIO]
        .pure(workUnit.userId == authContext.id)
        .ensureF(identity, Logger[DBIO].warning(s"user ${authContext.id} has no access to workUnit ${workUnit.id} ").as(NoAccessToWorkUnit))
        .void

  private def prepareDeleted(workUnit: WorkUnit): StepDBIO[WorkUnit] =
    (Clock[DBIO].instantNow.map { now =>
      workUnit.copy(state = WorkUnitState.Deleted, modifiedAt = WorkUnitModifiedAt(now), archivedAt = Some(WorkUnitArchivedAt(now)))
    }: DBIO[WorkUnit]).seal

  private def softDelete(workUnit: WorkUnit): StepDBIO[WorkUnit] =
    (Logger[DBIO].info(s"successfully deleter work unit ${workUnit.id}") *> workUnitRepository.update(workUnit))
      .valueOr(WorkUnitNotFound)

}

object WorkUnitDeleteService {
  sealed trait WorkUnitDeleteResult
  object WorkUnitDeleteResult {
    final case object OrganizationNotFound   extends WorkUnitDeleteResult
    final case object NoAccessToOrganization extends WorkUnitDeleteResult
    final case object WorkUnitNotFound       extends WorkUnitDeleteResult
    final case object NoAccessToWorkUnit     extends WorkUnitDeleteResult
    final case object Deleted                extends WorkUnitDeleteResult
  }
}
