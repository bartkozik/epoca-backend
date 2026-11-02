package pl.iterators.epoca.workunit.services

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.workunit.domain.WorkUnit
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitId
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitIdempotencyPreflightService.{CreatePreflightResult, UpdatePreflightResult}
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

class WorkUnitIdempotencyPreflightService(
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  workUnitRepository: WorkUnitRepository
)(implicit ec: ExecutionContext) {
  def preflightCreate(authContext: AuthContext, organizationSlug: OrganizationSlug): DBIO[CreatePreflightResult] =
    organizationRepository.find(OrganizationFilters(slug = Some(organizationSlug))).flatMap {
      case None               => DBIO.successful(CreatePreflightResult.OrganizationNotFound)
      case Some(organization) => authorizeCreate(authContext, organization)
    }

  def preflightUpdate(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    workUnitId: WorkUnitId
  ): DBIO[UpdatePreflightResult] =
    organizationRepository.find(OrganizationFilters(slug = Some(organizationSlug))).flatMap {
      case None => DBIO.successful(UpdatePreflightResult.OrganizationNotFound)
      case Some(organization) =>
        currentMembership(authContext, organization).flatMap {
          case None if !authContext.isSuperAdmin => DBIO.successful(UpdatePreflightResult.NoAccessToOrganization)
          case membership =>
            findTarget(organization.id, workUnitId).map {
              case None => UpdatePreflightResult.WorkUnitNotFound
              case Some(workUnit) if canAccess(authContext, membership, workUnit) =>
                UpdatePreflightResult.Allowed(organization.id)
              case Some(_) => UpdatePreflightResult.NoAccessToWorkUnit
            }
        }
    }

  private def authorizeCreate(authContext: AuthContext, organization: Organization): DBIO[CreatePreflightResult] =
    currentMembership(authContext, organization).map {
      case _ if authContext.isSuperAdmin => CreatePreflightResult.Allowed(organization.id)
      case Some(_)                       => CreatePreflightResult.Allowed(organization.id)
      case None                          => CreatePreflightResult.NoAccessToOrganization
    }

  private def currentMembership(authContext: AuthContext, organization: Organization): DBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) DBIO.successful(None)
    else
      organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authContext.id)))

  private def findTarget(organizationId: OrganizationId, workUnitId: WorkUnitId): DBIO[Option[WorkUnit]] =
    workUnitRepository.find(WorkUnitFilters(id = Some(workUnitId), organizationId = Some(organizationId), forUpdate = false))

  private def canAccess(
    authContext: AuthContext,
    membership: Option[OrganizationUser],
    workUnit: WorkUnit
  ): Boolean =
    authContext.isSuperAdmin || membership.exists(_.hasManagementRole) || workUnit.userId == authContext.id
}

object WorkUnitIdempotencyPreflightService {
  sealed trait CreatePreflightResult
  object CreatePreflightResult {
    final case class Allowed(organizationId: OrganizationId) extends CreatePreflightResult
    final case object OrganizationNotFound                   extends CreatePreflightResult
    final case object NoAccessToOrganization                 extends CreatePreflightResult
  }

  sealed trait UpdatePreflightResult
  object UpdatePreflightResult {
    final case class Allowed(organizationId: OrganizationId) extends UpdatePreflightResult
    final case object OrganizationNotFound                   extends UpdatePreflightResult
    final case object NoAccessToOrganization                 extends UpdatePreflightResult
    final case object WorkUnitNotFound                       extends UpdatePreflightResult
    final case object NoAccessToWorkUnit                     extends UpdatePreflightResult
  }
}
