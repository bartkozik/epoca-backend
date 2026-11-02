package pl.iterators.epoca.workunit.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitId, WorkUnitState}
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOrder}
import pl.iterators.epoca.workunit.repositories.WorkUnitOrderRepository.WorkUnitOrderFilters
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.{WorkUnitOrderRepository, WorkUnitRepository}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitListService.WorkUnitListResult._
import pl.iterators.epoca.workunit.services.WorkUnitListService.{
  OverrideContext,
  PreparedWorkUnits,
  WorkUnitListData,
  WorkUnitListOrderEntry,
  WorkUnitListResult
}
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

import java.sql.SQLException
import scala.annotation.tailrec

class WorkUnitListService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  userRepository: UserRepository,
  workUnitRepository: WorkUnitRepository,
  workUnitOrderRepository: WorkUnitOrderRepository,
  workUnitDtoService: WorkUnitDtoService
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[WorkUnitListResult](ioRuntime)
    with LoggingSupport {
  def list(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters
  ): IO[WorkUnitListResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      (for {
        prepared     <- listWorkUnitsWithCountAndFilter(authContext, organizationSlug, filter)
        workUnitDtos <- prepareDtos(prepared)
        result = WorkUnitListData(PaginatedResult(prepared.count, workUnitDtos), Nil)
      } yield WorkUnitListResult.Ok(result)).run
    }

  def listForExport(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters
  ): IO[WorkUnitListResult] =
    listForExport(authContext, organizationSlug, filter, WorkUnitListService.exportTransactionAttempts)

  private def listForExport(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters,
    attemptsRemaining: Int
  ): IO[WorkUnitListResult] =
    transactor
      .executeTransactionally(IsolationLevel.RepeatableRead) {
        (for {
          actor <- findActor(authContext, forUpdate = true)
          freshAuth = authContext.copy(isSuperAdmin = actor.isSuperAdmin)
          prepared     <- prepareExport(freshAuth, organizationSlug, filter)
          workUnitDtos <- prepareDtos(prepared)
          result = WorkUnitListData(PaginatedResult(prepared.count, workUnitDtos), Nil)
        } yield WorkUnitListResult.Ok(result)).run
      }
      .handleErrorWith { error =>
        if (attemptsRemaining > 1 && isSerializationFailure(error))
          listForExport(authContext, organizationSlug, filter, attemptsRemaining - 1)
        else IO.raiseError(error)
      }

  def listWithOrder(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    shouldReturnDraftIds: Boolean,
    filter: WorkUnitFilters
  ): IO[WorkUnitListResult] =
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      (for {
        prepared         <- listWorkUnitsWithCountAndFilter(authContext, organizationSlug, filter)
        workUnitDtos     <- prepareDtos(prepared)
        workUnitOrders   <- listWorkUnitsOrder(prepared.filters, authContext)
        workUnitDraftIds <- fetchDrafts(shouldReturnDraftIds, prepared.filters)
        orders = workUnitDtoService.prepareOrder(workUnitDtos, workUnitOrders)
        result =
          WorkUnitListData(PaginatedResult(prepared.count, workUnitDtos), WorkUnitListOrderEntry.from(orders), workUnitDraftIds)
      } yield WorkUnitListResult.Ok(result)).run
    }

  private def listWorkUnitsWithCountAndFilter(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters
  ): StepDBIO[PreparedWorkUnits] = for {
    freshAuth         <- resolveReportAuthContext(authContext)
    organization      <- findOrganization(organizationSlug)
    orgUserOpt        <- checkAccessToOrganization(freshAuth, organization)
    overrideContext   <- resolveOverrideContext(freshAuth, organization, orgUserOpt)
    restrictedFilters <- restrictFilters(filter, organization, orgUserOpt, freshAuth)
    authorizedFilters = applyOverrideScopeFilter(restrictedFilters, overrideContext)
    workUnits      <- listWorkUnits(authorizedFilters)
    workUnitsCount <- countWorkUnits(authorizedFilters)
  } yield PreparedWorkUnits(workUnits, workUnitsCount, authorizedFilters, overrideContext)

  private def prepareExport(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    filter: WorkUnitFilters
  ): StepDBIO[PreparedWorkUnits] = for {
    organization <- findOrganization(organizationSlug, forUpdate = true)
    _            <- checkAccessToOrganization(authContext, organization)
    projects <- projectRepository
                  .lockOrdered(ProjectFilters(organizationIds = Some(Seq(organization.id)), includeArchived = true))
                  .seal
    orgUserOpt   <- checkAccessToOrganization(authContext, organization, forUpdate = true)
    projectUsers <- listCurrentProjectUsers(authContext, forUpdate = true)
    snapshot = WorkUnitOverridePolicySnapshot(
                 actorUserId = authContext.id,
                 isSuperAdmin = authContext.isSuperAdmin,
                 organizationId = organization.id,
                 organizationUser = orgUserOpt,
                 projectUsers = projectUsers,
                 projects = projects
               )
    overrideContext = OverrideContext(snapshot, WorkUnitOverridePolicy.scope(snapshot))
    restrictedFilters <- restrictFilters(filter, organization, orgUserOpt, authContext)
    // The previous ordinary export used WorkUnitFilters.from, which restricts a
    // non-manager to their own user ID. Preserve that explicit self-export boundary.
    _ <- overrideContext.scope
           .liftSealed[DBIO, WorkUnitListResult]
           .ensure(
             scope =>
               scope != WorkUnitOverrideScope.None ||
                 (orgUserOpt.exists(_.archivedAt.isEmpty) && restrictedFilters.userIds.contains(Seq(authContext.id))),
             OverrideForbidden
           )
           .void
    authorizedFilters = applyOverrideScopeFilter(restrictedFilters, Some(overrideContext))
    workUnits      <- listWorkUnits(authorizedFilters)
    workUnitsCount <- countWorkUnits(authorizedFilters)
  } yield PreparedWorkUnits(workUnits, workUnitsCount, authorizedFilters, Some(overrideContext))

  private def resolveReportAuthContext(authContext: AuthContext): StepDBIO[AuthContext] =
    findActor(authContext, forUpdate = false).map(actor => authContext.copy(isSuperAdmin = actor.isSuperAdmin))

  private def findActor(authContext: AuthContext, forUpdate: Boolean): StepDBIO[User] =
    userRepository
      .find(UserFilters(id = Some(authContext.id), includeArchived = true, forUpdate = forUpdate))
      .valueOrF {
        Logger[DBIO]
          .warning(s"user ${authContext.id} does not exist")
          .as(NoAccessToOrganization)
      }
      .ensure(_.archivedAt.isEmpty, NoAccessToOrganization)

  private def findOrganization(organizationSlug: OrganizationSlug, forUpdate: Boolean = false): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(organizationSlug), forUpdate = forUpdate))
      .valueOrF {
        Logger[DBIO].error(s"organization $organizationSlug does not exist").as(OrganizationNotFound)
      }

  private def checkAccessToOrganization(
    authContext: AuthContext,
    organization: Organization,
    forUpdate: Boolean = false
  ): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) None.liftSealed[DBIO, WorkUnitListResult]
    else
      organizationUserRepository
        .find(
          OrganizationUserFilters(
            organizationId = Some(organization.id),
            userId = Some(authContext.id),
            includeArchived = forUpdate,
            forUpdate = forUpdate
          )
        )
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization ${organization.id}")
            .as(NoAccessToOrganization)
        }
        .ensure(_.archivedAt.isEmpty, NoAccessToOrganization)
        .map(user => Option(user))

  private def restrictFilters(
    filter: WorkUnitFilters,
    organization: Organization,
    orgUserOpt: Option[OrganizationUser],
    authContext: AuthContext
  ): StepDBIO[WorkUnitFilters] =
    WorkUnitFilters
      .from(filter, organization, orgUserOpt, authContext)
      .liftSealed[DBIO, WorkUnitListResult]

  private def resolveOverrideContext(
    authContext: AuthContext,
    organization: Organization,
    orgUserOpt: Option[OrganizationUser]
  ): StepDBIO[Option[OverrideContext]] =
    for {
      projects <- projectRepository
                    .list(ProjectFilters(organizationIds = Some(Seq(organization.id)), includeArchived = true))
                    .seal
      projectUsers <- listCurrentProjectUsers(authContext)
      snapshot = WorkUnitOverridePolicySnapshot(
                   actorUserId = authContext.id,
                   isSuperAdmin = authContext.isSuperAdmin,
                   organizationId = organization.id,
                   organizationUser = orgUserOpt,
                   projectUsers = projectUsers,
                   projects = projects
                 )
      scope = WorkUnitOverridePolicy.scope(snapshot)
    } yield Some(OverrideContext(snapshot, scope))

  private def listCurrentProjectUsers(authContext: AuthContext, forUpdate: Boolean = false): StepDBIO[Seq[ProjectUser]] =
    if (authContext.isSuperAdmin) Seq.empty[ProjectUser].liftSealed[DBIO, WorkUnitListResult]
    else {
      val filter = ProjectUserFilters(userId = Some(authContext.id), includeArchived = true)
      if (forUpdate) projectUserRepository.lockOrdered(filter).seal else projectUserRepository.list(filter).seal
    }

  private def applyOverrideScopeFilter(filter: WorkUnitFilters, overrideContext: Option[OverrideContext]): WorkUnitFilters =
    overrideContext.map(_.scope) match {
      case Some(WorkUnitOverrideScope.Owned(projectIds)) if !filter.userIds.contains(Seq(overrideContext.get.snapshot.actorUserId)) =>
        filter.copy(reportAuthorizedProjectIds = Some(projectIds.toSeq.sortBy(_.toString)))
      case _ => filter
    }

  private def prepareDtos(prepared: PreparedWorkUnits): StepDBIO[Seq[WorkUnitDto]] = {
    val action: DBIO[Seq[WorkUnitDto]] = workUnitDtoService
      .prepareDtos(prepared.workUnits)
      .map { dtos =>
        val byId = prepared.workUnits.map(unit => unit.id -> unit).toMap
        dtos.map { dto =>
          val permissions = for {
            context <- prepared.overrideContext
            unit    <- byId.get(dto.id)
          } yield WorkUnitOverrideDto.permissions(context.snapshot, unit)
          dto.copy(`override` = dto.`override`.copy(permissions = permissions.getOrElse(WorkUnitOverridePermissionsDto())))
        }
      }
    action.seal
  }

  @tailrec
  private def isSerializationFailure(error: Throwable): Boolean = error match {
    case sqlError: SQLException if sqlError.getSQLState == WorkUnitListService.serializationFailureSqlState => true
    case _ if error.getCause != null && (error.getCause ne error)                                           => isSerializationFailure(error.getCause)
    case _                                                                                                  => false
  }

  private def listWorkUnits(filters: WorkUnitFilters): StepDBIO[Seq[WorkUnit]] =
    workUnitRepository.list(filters).seal

  private def countWorkUnits(filters: WorkUnitFilters): StepDBIO[Int] =
    workUnitRepository.count(filters).seal

  private def fetchDrafts(shouldReturnDraftIds: Boolean, workUnitFilters: WorkUnitFilters): StepDBIO[Option[Seq[WorkUnitId]]] = {
    lazy val filter =
      WorkUnitFilters(states = Some(Seq(WorkUnitState.Draft)), userIds = workUnitFilters.userIds, organizationId = workUnitFilters.organizationId)
    lazy val result: DBIO[Option[Seq[WorkUnitId]]] = workUnitRepository
      .list(filter)
      .map(rows => Some(rows.map(_.id)))

    if (shouldReturnDraftIds) result.seal
    else None.liftSealed[DBIO, WorkUnitListResult]
  }

  private def listWorkUnitsOrder(filters: WorkUnitFilters, authContext: AuthContext): StepDBIO[Seq[WorkUnitOrder]] = {
    val orderFilters = WorkUnitOrderFilters(
      organizationId = filters.organizationId,
      userId = Some(authContext.id),
      dateFrom = filters.dateFrom,
      dateTo = filters.dateTo
    )
    workUnitOrderRepository.list(orderFilters).seal
  }

}

object WorkUnitListService {
  private val exportTransactionAttempts    = 3
  private val serializationFailureSqlState = "40001"

  private final case class OverrideContext(snapshot: WorkUnitOverridePolicySnapshot, scope: WorkUnitOverrideScope)
  private final case class PreparedWorkUnits(
    workUnits: Seq[WorkUnit],
    count: Int,
    filters: WorkUnitFilters,
    overrideContext: Option[OverrideContext])

  case class WorkUnitListOrderEntry(date: WorkUnitDate, ids: Seq[WorkUnitId])
  object WorkUnitListOrderEntry {
    def from(orders: Map[WorkUnitDate, Seq[WorkUnitId]]): Seq[WorkUnitListOrderEntry] =
      orders.map { case (date, ids) => WorkUnitListOrderEntry(date, ids) }.toSeq
  }
  case class WorkUnitListData(
    workUnits: PaginatedResult[WorkUnitDto],
    orders: Seq[WorkUnitListOrderEntry],
    draftIds: Option[Seq[WorkUnitId]] = None)
  sealed trait WorkUnitListResult
  object WorkUnitListResult {
    final case object OrganizationNotFound      extends WorkUnitListResult
    final case object NoAccessToOrganization    extends WorkUnitListResult
    final case object OverrideForbidden         extends WorkUnitListResult
    final case class Ok(data: WorkUnitListData) extends WorkUnitListResult
  }
}
