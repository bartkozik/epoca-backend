package pl.iterators.epoca.stats.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{Config => TsConfig}
import io.circe.Json
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery._
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository
import pl.iterators.epoca.stats.repositories.WorkUnitPivotQueryRepository.{AggregateRow, CoordinateCounts}
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result
import pl.iterators.epoca.stats.services.WorkUnitPivotQueryService.Result._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class WorkUnitPivotQueryService(
  config: WorkUnitPivotQueryService.Config,
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  repository: WorkUnitPivotQueryRepository,
  validator: WorkUnitPivotQueryRequestValidator,
  assembler: WorkUnitPivotQueryAssembler
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[Result](ioRuntime)
    with LoggingSupport {

  def query(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    rawRequest: Json,
    filters: GlobalFilters
  ): IO[Result] =
    validator.validate(rawRequest) match {
      case Left(error)    => IO.pure(InvalidConfiguration(error))
      case Right(request) => queryValidated(authContext, organizationSlug, request, filters)
    }

  private[epoca] def queryOAuthAction(
    organizationId: pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId,
    allowedProjectIds: Seq[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId],
    rawRequest: Json,
    filters: GlobalFilters
  ): DBIO[Result] =
    validator.validate(rawRequest) match {
      case Left(error)    => DBIO.successful(InvalidConfiguration(error))
      case Right(request) => queryOAuthValidatedAction(organizationId, allowedProjectIds, request, filters)
    }

  private[epoca] def queryOAuthValidatedAction(
    organizationId: pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId,
    allowedProjectIds: Seq[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId],
    request: Request,
    filters: GlobalFilters
  ): DBIO[Result] = {
    val boundedFilters = filters.copy(reportAuthorizedProjectIds = Some(allowedProjectIds))
    val query = for {
      counts                <- repository.coordinateCounts(organizationId, request, boundedFilters).seal
      displayValueCellCount <- guardDisplayValueCellCount(counts, request.values.size)
      aggregateRows         <- repository.aggregate(organizationId, request, boundedFilters).seal
      filterMembers         <- repository.availableFilterMembers(organizationId, request, boundedFilters).seal
      response              <- assemble(request, aggregateRows, filterMembers, displayValueCellCount)
    } yield Ok(response)

    query.run
  }

  def queryValidated(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    request: Request,
    filters: GlobalFilters
  ): IO[Result] = {
    val query = for {
      organization          <- findOrganization(organizationSlug)
      _                     <- checkAccessToOrganization(authContext, organization)
      counts                <- repository.coordinateCounts(organization.id, request, filters).seal
      displayValueCellCount <- guardDisplayValueCellCount(counts, request.values.size)
      aggregateRows         <- repository.aggregate(organization.id, request, filters).seal
      filterMembers         <- repository.availableFilterMembers(organization.id, request, filters).seal
      response              <- assemble(request, aggregateRows, filterMembers, displayValueCellCount)
    } yield Ok(response)

    transactor.executeTransactionally(IsolationLevel.RepeatableRead)(query.run)
  }

  private def findOrganization(organizationSlug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug)))
      .valueOrF {
        Logger[DBIO].warning(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    if (authContext.isSuperAdmin) ().liftSealed[DBIO, Result]
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .ensureF(
          organizationUser => OrganizationUserRole.managementAccessRoles.intersect(organizationUser.roles).nonEmpty,
          Logger[DBIO]
            .warning(s"user ${authContext.id} roles are insufficient to access organization ${organization.id}")
            .as(InsufficientRole)
        )
        .void

  private def guardDisplayValueCellCount(counts: CoordinateCounts, requestedValueCount: Int): StepDBIO[Long] = {
    val estimated = WorkUnitPivotQueryService.saturatingDisplayValueCellCount(counts, requestedValueCount)
    estimated
      .liftSealed[DBIO, Result]
      .ensureF(
        _ <= config.maxDisplayValueCellCount,
        Logger[DBIO]
          .warning(s"pivot display value cell estimate $estimated exceeds configured limit ${config.maxDisplayValueCellCount}")
          .as(TooLarge(ResultTooLargeError(estimatedDisplayValueCellCount = estimated, maxDisplayValueCellCount = config.maxDisplayValueCellCount)))
      )
  }

  private def assemble(
    request: Request,
    aggregateRows: Seq[AggregateRow],
    filterMembers: Map[WorkUnitPivotTableKey, Seq[FieldValue]],
    displayValueCellCount: Long
  ): StepDBIO[Response] =
    assembler
      .assemble(request, aggregateRows, filterMembers, displayValueCellCount, config.maxDisplayValueCellCount)
      .liftSealed[DBIO, Result]
}

object WorkUnitPivotQueryService {
  final case class Config(maxDisplayValueCellCount: Long) {
    require(maxDisplayValueCellCount > 0L, "stats.pivot.maxDisplayValueCellCount must be positive")
  }

  object Config {
    def apply(config: TsConfig): Config = Config(config.getLong("stats.pivot.maxDisplayValueCellCount"))
  }

  private[stats] def saturatingDisplayValueCellCount(counts: CoordinateCounts, requestedValueCount: Int): Long = {
    val rowCount    = BigInt(counts.row).max(BigInt(0))
    val columnCount = BigInt(counts.column).max(BigInt(0))
    val valueCount  = BigInt(requestedValueCount).max(BigInt(0))
    (rowCount * columnCount * valueCount).min(BigInt(Long.MaxValue)).toLong
  }

  sealed trait Result
  object Result {
    final case class Ok(response: Response)                                 extends Result
    final case class InvalidConfiguration(error: InvalidConfigurationError) extends Result
    final case class TooLarge(error: ResultTooLargeError)                   extends Result
    case object OrganizationNotFound                                        extends Result
    case object NoAccessToOrganization                                      extends Result
    case object InsufficientRole                                            extends Result
  }
}
