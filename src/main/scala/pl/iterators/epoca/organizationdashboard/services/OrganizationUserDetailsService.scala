package pl.iterators.epoca.organizationdashboard.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientName}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.{OrganizationUserDetailsResult, OrganizationUserDetailsRow}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName, ProjectRole}
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class OrganizationUserDetailsService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  clientRepository: ClientRepository,
  projectUserRepository: ProjectUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationUserDetailsResult](ioRuntime)
    with LoggingSupport {
  def details(
    auth: AuthContext,
    slug: OrganizationSlug,
    userId: UserId
  ): IO[OrganizationUserDetailsResult] = {
    val query = for {
      organization <- findOrganization(slug)
      _            <- checkPermission(auth, organization, userId)
      _            <- validateIsMember(organization, userId)
      projectMap   <- getProjectMap(organization)
      clientMap    <- getClientMap(organization)
      userProjects <- getUserProjects(userId, projectMap.keys.toSeq)
      data         <- prepareDto(userProjects, projectMap, clientMap)
    } yield OrganizationUserDetailsResult.Ok(data)
    transactor.execute(query.run)
  }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationUserDetailsResult.OrganizationNotFound)
      }

  private def checkPermission(
    auth: AuthContext,
    organization: Organization,
    userId: UserId
  ): StepDBIO[Unit] =
    if (auth.id == organization.createdBy || auth.isSuperAdmin || auth.id == userId) Sealed.liftF(())
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(auth.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${auth.id} has no access to user $userId details")
            .as(OrganizationUserDetailsResult.OrganizationNoAccess)
        }
        .ensureF(
          _.hasManagementRole,
          Logger[DBIO]
            .warning(s"user ${auth.id} has no access to user $userId details")
            .as(OrganizationUserDetailsResult.OrganizationNoAccess)
        )
        .void

  private def validateIsMember(organization: Organization, userId: UserId): StepDBIO[Unit] = {
    val filters = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
    organizationUserRepository
      .find(filters)
      .valueOrF {
        Logger[DBIO].warning(s"user $userId is not a member of organization ${organization.id}").as(OrganizationUserDetailsResult.NotAMember)
      }
      .void
  }

  private def getProjectMap(organization: Organization): StepDBIO[Map[ProjectId, (ProjectName, ClientId)]] = {
    val filters = ProjectFilters(organizationIds = Some(Seq(organization.id)))
    val result: DBIO[Map[ProjectId, (ProjectName, ClientId)]] = projectRepository
      .list(filters)
      .map(_.map(row => row.id -> (row.name, row.clientId)).toMap)
    result.seal
  }

  private def getClientMap(organization: Organization): StepDBIO[Map[ClientId, ClientName]] = {
    val filters = ClientFilters(organizationIds = Some(Seq(organization.id)))
    val result: DBIO[Map[ClientId, ClientName]] = clientRepository
      .list(filters)
      .map(_.map(row => row.id -> row.name).toMap)
    result.seal
  }

  private def getUserProjects(userId: UserId, organizationProjectIds: Seq[ProjectId]): StepDBIO[Seq[ProjectUser]] = {
    val filters = ProjectUserFilters(userId = Some(userId), projectIds = Some(organizationProjectIds))
    val result: DBIO[Seq[ProjectUser]] = projectUserRepository
      .list(filters)
    result.seal
  }

  private def prepareDto(
    projectUsers: Seq[ProjectUser],
    projectMap: Map[ProjectId, (ProjectName, ClientId)],
    clientMap: Map[ClientId, ClientName]
  ): StepDBIO[Seq[OrganizationUserDetailsRow]] =
    Sealed.liftF(projectUsers.flatMap { projectUser =>
      for {
        (projectName, clientId) <- projectMap.get(projectUser.projectId)
        clientName              <- clientMap.get(clientId)
      } yield OrganizationUserDetailsRow(projectUser.role, projectUser.projectId, clientId, projectName, clientName)
    })
}

object OrganizationUserDetailsService {

  case class OrganizationUserDetailsRow(
    role: ProjectRole,
    projectId: ProjectId,
    clientId: ClientId,
    projectName: ProjectName,
    clientName: ClientName)
  sealed trait OrganizationUserDetailsResult
  object OrganizationUserDetailsResult {
    case class Ok(data: Seq[OrganizationUserDetailsRow]) extends OrganizationUserDetailsResult
    case object OrganizationNotFound                     extends OrganizationUserDetailsResult
    case object OrganizationNoAccess                     extends OrganizationUserDetailsResult
    case object NotAMember                               extends OrganizationUserDetailsResult
  }
}
