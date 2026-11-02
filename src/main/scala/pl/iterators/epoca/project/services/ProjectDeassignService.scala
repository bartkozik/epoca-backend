package pl.iterators.epoca.project.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectRole, ProjectUserArchivedAt}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectDeassignService.ProjectDeassignResult
import pl.iterators.epoca.project.services.ProjectDeassignService.ProjectDeassignResult._
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectDeassignService(
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectDeassignResult](ioRuntime)
    with LoggingSupport {

  def deassign(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ProjectId,
    userId: UserId
  ): IO[ProjectDeassignResult] = transactor.execute {
    (for {
      _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, projectId=$id, userId=$userId").seal
      organization <- findOrganization(organizationSlug)
      orgUser      <- findOrganizationUser(authContext, organization.id)
      project      <- findProject(id)
      projectUser  <- findProjectUser(authContext, project)
      _            <- validatePermission(authContext, orgUser, projectUser)
      user         <- findUser(userId)
      projectUser  <- findProjectUser(project.id, user.id)
      _            <- checkNotCreator(project, user)
      softDeleted  <- prepareDeleted(projectUser)
      _            <- deassign(softDeleted)
    } yield Deassigned).run
  }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF(Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound))

  private def findOrganizationUser(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF(
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no access to organization $id")
            .as(NoAccessToOrganization)
        )
        .map(Option.apply)

  private def findProject(id: ProjectId): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(id = Some(id)))
      .valueOrF {
        Logger[DBIO].error(s"project $id does not exist").as(ProjectNotFound)
      }

  private def findProjectUser(authContext: AuthContext, project: Project): StepDBIO[Option[ProjectUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authContext.id), role = Some(ProjectRole.Owner)))
        .seal

  private def findUser(userId: UserId): StepDBIO[User] =
    userRepository.find(UserFilters(id = Some(userId))).valueOrF {
      Logger[DBIO].warning(s"user $userId does not exist").as(UserNotFound)
    }

  private def findProjectUser(projectId: ProjectId, userId: UserId): StepDBIO[ProjectUser] =
    projectUserRepository
      .find(ProjectUserFilters(projectIds = Some(Seq(projectId)), userId = Some(userId)))
      .valueOrF(Logger[DBIO].error(s"user $userId is already not assigned to project $projectId").as(UserAlreadyUnassigned))

  private def validatePermission(
    authContext: AuthContext,
    optOrgUser: Option[OrganizationUser],
    optProjectUser: Option[ProjectUser]
  ): StepDBIO[Unit] = {
    val isProjectOwner   = optProjectUser.map(_.role).contains(ProjectRole.Owner)
    val isAdminOrManager = optOrgUser.isEmpty || optOrgUser.exists(_.hasManagementRole)

    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(_ => isProjectOwner || isAdminOrManager, Logger[DBIO].warning(s"user ${authContext.id} has no permission").as(OperationNotPermitted))
  }

  private def checkNotCreator(project: Project, user: User): StepDBIO[Unit] =
    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(
        _ => project.createdBy != user.id,
        Logger[DBIO]
          .error(s"Can not deassign creator ${user.id} from project ${project.id}")
          .as(CreatorNotDeassignable)
      )

  private def prepareDeleted(projectUser: ProjectUser): StepDBIO[ProjectUser] =
    (Clock[DBIO].instantNow.map { now =>
      projectUser.copy(archivedAt = Some(ProjectUserArchivedAt(now)))
    }: DBIO[ProjectUser]).seal

  private def deassign(projectUser: ProjectUser): StepDBIO[ProjectUser] =
    (Logger[DBIO].info(s"successfully deassigned user ${projectUser.userId} from project ${projectUser.projectId}") *>
      projectUserRepository.update(projectUser)).valueOr(ProjectNotFound)
}

object ProjectDeassignService {
  sealed trait ProjectDeassignResult
  object ProjectDeassignResult {
    final case object Deassigned             extends ProjectDeassignResult
    final case object OrganizationNotFound   extends ProjectDeassignResult
    final case object NoAccessToOrganization extends ProjectDeassignResult
    final case object OperationNotPermitted  extends ProjectDeassignResult
    final case object ProjectNotFound        extends ProjectDeassignResult
    final case object UserNotFound           extends ProjectDeassignResult
    final case object UserAlreadyUnassigned  extends ProjectDeassignResult
    final case object CreatorNotDeassignable extends ProjectDeassignResult
  }
}
