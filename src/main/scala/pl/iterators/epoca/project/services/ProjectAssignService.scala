package pl.iterators.epoca.project.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories._
import pl.iterators.epoca.project.services.ProjectAssignService.ProjectAssignResult._
import pl.iterators.epoca.project.services.ProjectAssignService._
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectAssignService(
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectAssignResult](ioRuntime)
    with LoggingSupport {

  def assign(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ProjectId,
    userId: UserId,
    request: ProjectAssignRequest
  ): IO[ProjectAssignResult] = transactor
    .executeTransactionally {
      (for {
        _                <- Logger[DBIO].info(s"auth.id=${authContext.id}, projectId=$id, userId=$userId, request=$request").seal
        organization     <- findOrganization(organizationSlug)
        orgUser          <- findOrganizationUser(authContext, organization.id)
        project          <- findProject(id, organization)
        projectUser      <- findProjectUser(authContext, project)
        _                <- validatePermission(authContext, orgUser, projectUser)
        user             <- findUser(userId)
        _                <- validateUserIsMember(userId, organization)
        maybeProjectUser <- findCurrentProjectUser(project.id, user.id, request)
        _                <- archiveCurrentProjectUser(project, maybeProjectUser)
        projectUser      <- prepareProjectUser(project, request, user)
        _                <- insertProjectUser(projectUser)
      } yield Assigned).run
    }
    .handleErrorWith {
      case ProjectUserNotPermitted => IO.pure(UserNotFound)
      case error                   => IO.raiseError(error)
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

  private def findProject(id: ProjectId, organization: Organization): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(id), forUpdate = true))
      .valueOrF {
        Logger[DBIO].error(s"project $id does not exist").as(ProjectNotFound)
      }

  private def findProjectUser(authContext: AuthContext, project: Project): StepDBIO[Option[ProjectUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authContext.id), role = Some(ProjectRole.Owner)))
        .seal

  private def validatePermission(
    authContext: AuthContext,
    optOrgUser: Option[OrganizationUser],
    optProjectUser: Option[ProjectUser]
  ): StepDBIO[Unit] = {
    val isProjectOwner   = optProjectUser.exists(_.role == ProjectRole.Owner)
    val isAdminOrManager = optOrgUser.isEmpty || optOrgUser.exists(_.hasManagementRole)

    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(_ => isProjectOwner || isAdminOrManager, Logger[DBIO].warning(s"user ${authContext.id} has no permission").as(OperationNotPermitted))
  }

  private def findUser(userId: UserId): StepDBIO[User] = {
    val notFound = Logger[DBIO].warning(s"user $userId does not exist").as(UserNotFound)

    userRepository
      .find(UserFilters(id = Some(userId)))
      .valueOrF(notFound)
      .ensureF(_.accountType == UserAccountType.Human, notFound)
  }

  private def validateUserIsMember(userId: UserId, organization: Organization): StepDBIO[Unit] =
    organizationUserRepository
      .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId)))
      .valueOrF {
        Logger[DBIO].warning(s"user $userId isn't a member of that organization").as(NotAMember)
      }
      .void

  private def findCurrentProjectUser(
    projectId: ProjectId,
    userId: UserId,
    request: ProjectAssignRequest
  ): StepDBIO[Option[ProjectUser]] =
    projectUserRepository
      .find(ProjectUserFilters(projectIds = Some(Seq(projectId)), userId = Some(userId), forUpdate = true))
      .ensureF(
        !_.exists(_.role == request.role),
        Logger[DBIO].error(s"user $userId is already assigned as ${request.role} to project $projectId").as(UserAlreadyAssigned)
      )

  private def archiveCurrentProjectUser(project: Project, maybeProjectUser: Option[ProjectUser]): StepDBIO[Unit] = maybeProjectUser match {
    case None => Sealed.liftF(())
    case Some(projectUser: ProjectUser) =>
      for {
        _           <- checkNotCreator(project, projectUser)
        softDeleted <- prepareDeleted(projectUser)
        _           <- deassign(softDeleted)
      } yield ()
  }

  private def checkNotCreator(project: Project, projectUser: ProjectUser): StepDBIO[Unit] =
    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(
        _ => project.createdBy != projectUser.userId,
        Logger[DBIO]
          .error(s"Can not deassign creator ${projectUser.userId} from project ${project.id}")
          .as(CreatorNotDeassignable)
      )

  private def prepareDeleted(projectUser: ProjectUser): StepDBIO[ProjectUser] =
    (Clock[DBIO].instantNow.map { now =>
      projectUser.copy(archivedAt = Some(ProjectUserArchivedAt(now)))
    }: DBIO[ProjectUser]).seal

  private def deassign(projectUser: ProjectUser): StepDBIO[Unit] =
    (Logger[DBIO].info(s"successfully deassigned user ${projectUser.userId} from project ${projectUser.projectId}") *>
      projectUserRepository.update(projectUser)).seal.void

  private def prepareProjectUser(
    project: Project,
    request: ProjectAssignRequest,
    user: User
  ): StepDBIO[ProjectUser] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, ProjectUserId].map { projectUserId =>
        request
          .into[ProjectUser]
          .withFieldConst(_.id, projectUserId)
          .withFieldConst(_.projectId, project.id)
          .withFieldConst(_.userId, user.id)
          .withFieldConst(_.role, request.role)
          .withFieldConst(_.createdAt, ProjectUserCreatedAt(now))
          .withFieldConst(_.archivedAt, None)
          .transform
      }
    }: DBIO[ProjectUser]).seal

  private def insertProjectUser(projectUser: ProjectUser): StepDBIO[ProjectUser] =
    (Logger[DBIO].info(s"successfully assigned user ${projectUser.userId} to project ${projectUser.projectId}") *> projectUserRepository.insert(
      projectUser
    )).seal
}

object ProjectAssignService {
  final case class ProjectAssignRequest(role: ProjectRole)

  sealed trait ProjectAssignResult
  object ProjectAssignResult {
    final case object Assigned               extends ProjectAssignResult
    final case object OrganizationNotFound   extends ProjectAssignResult
    final case object NoAccessToOrganization extends ProjectAssignResult
    final case object ProjectNotFound        extends ProjectAssignResult
    final case object UserNotFound           extends ProjectAssignResult
    final case object NotAMember             extends ProjectAssignResult
    final case object OperationNotPermitted  extends ProjectAssignResult
    final case object CreatorNotDeassignable extends ProjectAssignResult
    final case object UserAlreadyAssigned    extends ProjectAssignResult
  }
}
