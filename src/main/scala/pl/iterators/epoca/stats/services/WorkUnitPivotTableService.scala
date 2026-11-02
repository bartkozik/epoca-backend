package pl.iterators.epoca.stats.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.{WorkUnitPivotTableProcessor, WorkUnitPivotTableRow}
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.WorkUnitPivotTableResult._
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.{WorkUnitFilters, WorkUnitWithClientId}
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class WorkUnitPivotTableService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitRepository: WorkUnitRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitPivotTableResult](ioRuntime)
    with LoggingSupport {
  def buildPivotTable(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: WorkUnitPivotTableRequest,
    filter: WorkUnitFilters
  ): IO[WorkUnitPivotTableResult] = {
    val query = for {
      _            <- validateRequest(request)
      organization <- findOrganization(organizationSlug)
      _            <- checkAccessToOrganization(authContext, organization)
      workUnits    <- listWorkUnits(organization, filter)
      pivotTable   <- createPivotTable(request, workUnits)
    } yield Ok(pivotTable)
    transactor.execute(query.run)
  }

  private[epoca] def buildOAuthPivotTableAction(
    organizationId: pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId,
    allowedProjectIds: Seq[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId],
    request: WorkUnitPivotTableRequest,
    filter: WorkUnitFilters
  ): DBIO[WorkUnitPivotTableResult] = {
    val query = for {
      _ <- validateRequest(request)
      workUnits <-
        workUnitRepository
          .listWithClientId(
            filter.copy(organizationId = Some(organizationId), reportAuthorizedProjectIds = Some(allowedProjectIds), pageSizeAndNumber = None)
          )
          .seal
      pivotTable <- createPivotTable(request, workUnits)
    } yield Ok(pivotTable)
    query.run
  }

  private def validateRequest(request: WorkUnitPivotTableRequest): StepDBIO[Unit] =
    request
      .liftSealed[DBIO, WorkUnitPivotTableResult]
      .ensureF(_.keys.nonEmpty, Logger[DBIO].warning("empty keys requested").as(KeysRequired))
      .ensureF(_.values.nonEmpty, Logger[DBIO].warning("empty values requested").as(ValuesRequired))
      .void

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug)))
      .valueOrF {
        Logger[DBIO].warning(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) ().liftSealed[DBIO, WorkUnitPivotTableResult]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .ensureF(
          orgUser => OrganizationUserRole.managementAccessRoles.intersect(orgUser.roles).nonEmpty,
          Logger[DBIO]
            .warning(s"user ${authContext.id} roles are insufficient to access organization ${organization.id}")
            .as(InsufficientRole)
        )
        .void

  private def listWorkUnits(organization: Organization, filter: WorkUnitFilters): StepDBIO[Seq[WorkUnitWithClientId]] = {
    val restrictedFilter = filter.copy(organizationId = Some(organization.id))
    workUnitRepository.listWithClientId(restrictedFilter).seal
  }

  private def createPivotTable(request: WorkUnitPivotTableRequest, units: Seq[WorkUnitWithClientId]): StepDBIO[Seq[WorkUnitPivotTableRow]] = {
    val values            = request.values.distinct
    val initialProcessors = Seq(WorkUnitPivotTableProcessor(units))
    request.keys.distinct
      .foldLeft(initialProcessors) { case (builders, key) =>
        builders.flatMap(WorkUnitPivotTableProcessor.buildFrom(_, key))
      }
      .map(WorkUnitPivotTableRow.from(values, _))
  }.liftSealed[DBIO, WorkUnitPivotTableResult]
}

object WorkUnitPivotTableService {
  case class WorkUnitPivotTableRequest(keys: Seq[WorkUnitPivotTableKey], values: Seq[WorkUnitPivotTableValue])

  sealed trait WorkUnitPivotTableResult
  object WorkUnitPivotTableResult {
    case class Ok(data: Seq[WorkUnitPivotTableRow]) extends WorkUnitPivotTableResult
    case object OrganizationNotFound                extends WorkUnitPivotTableResult
    case object NoAccessToOrganization              extends WorkUnitPivotTableResult
    case object InsufficientRole                    extends WorkUnitPivotTableResult
    case object KeysRequired                        extends WorkUnitPivotTableResult
    case object ValuesRequired                      extends WorkUnitPivotTableResult
  }
}
