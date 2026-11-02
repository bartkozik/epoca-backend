package pl.iterators.epoca.project.services

import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser, ProjectUserRole}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectUpdateService.ProjectUpdateResult._
import pl.iterators.epoca.project.services.ProjectUpdateService.{ProjectUpdateRequest, ProjectUpdateResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class ProjectUpdateService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository,
  projectUserRepository: ProjectUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[ProjectUpdateResult](ioRuntime)
    with LoggingSupport {

  def update(
    authContext: AuthContext,
    organizationSlug: OrganizationSlug,
    id: ProjectId,
    request: ProjectUpdateRequest
  ): IO[ProjectUpdateResult] =
    transactor
      .executeTransactionally {
        (for {
          _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$organizationSlug, projectId=$id").seal
          organization <- findOrganization(organizationSlug)
          orgUser      <- checkAccessToOrganization(authContext, organization.id)
          _            <- findProjectUserIfNotManager(orgUser, id, authContext.id)
          project      <- findProject(organization, id)
          _            <- validateUniqueProjectName(organization, project, request)
          projectUsers <- listProjectUsers(project)
          _            <- checkUsersHaveAccessToOrganization(organization, request)
          projectUserChanges = ProjectUser.getProjectUserChanges(request.ownerIds, request.memberIds, projectUsers)
          _        <- checkNotCreator(project, projectUsers, projectUserChanges.toDeassign)
          toUpdate <- prepareProject(authContext, project, request)
          result   <- updateProject(toUpdate)
          _        <- deassignUsers(project, projectUserChanges.toDeassign)
          toAssign <- prepareAssignProjectUsers(project, projectUserChanges.toAssign)
          _        <- assignUsers(project, toAssign)
        } yield Updated(result)).run
      }
      .handleErrorWith {
        case ProjectUserNotPermitted => IO.pure(ProjectUsersHaveNoAccessToOrganization)
        case error                   => IO.raiseError(error)
      }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF(Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationNotFound))

  private def checkAccessToOrganization(authContext: AuthContext, id: OrganizationId): StepDBIO[Option[OrganizationUser]] =
    if (authContext.isSuperAdmin) Sealed.liftF(None)
    else
      organizationUserRepository
        .find(OrganizationUserFilters(organizationId = Some(id), userId = Some(authContext.id)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user ${authContext.id} has no role in organization $id")
            .as(NoAccessToOrganization)
        }
        .map(Option.apply)

  private def findProjectUserIfNotManager(
    orgUser: Option[OrganizationUser],
    id: ProjectId,
    userId: UserId
  ): StepDBIO[Unit] =
    if (orgUser.isEmpty || orgUser.exists(_.hasManagementRole)) Sealed.liftF(())
    else
      projectUserRepository
        .find(ProjectUserFilters(projectIds = Some(Seq(id)), userId = Some(userId), role = Some(ProjectRole.Owner)))
        .valueOrF {
          Logger[DBIO]
            .warning(s"user $userId is not an owner in project $id")
            .as(NotProjectOwner)
        }
        .void

  private def findProject(organization: Organization, id: ProjectId): StepDBIO[Project] =
    projectRepository
      .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(id), forUpdate = true))
      .valueOrF(Logger[DBIO].error(s"project $id does not exist").as(ProjectNotFound))

  private def validateUniqueProjectName(
    organization: Organization,
    project: Project,
    request: ProjectUpdateRequest
  ): StepDBIO[Unit] =
    request.name match {
      case None                               => Sealed.liftF(())
      case Some(name) if name == project.name => Sealed.liftF(())
      case Some(name) =>
        projectRepository
          .find(ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(name)))
          .ensureF(
            _.isEmpty,
            Logger[DBIO].error(s"project with name $name can't be created - another project with the same name is active").as(ProjectNameNotUnique)
          )
          .void

    }

  private def prepareProject(
    authContext: AuthContext,
    project: Project,
    request: ProjectUpdateRequest
  ): StepDBIO[Project] =
    Clock[DBIO].instantNow.seal.map { now =>
      project
        .using(request)
        .ignoreRedundantPatcherFields
        .ignoreNoneInPatch
        .patch
        .copy(modifiedAt = ProjectModifiedAt(now), modifiedBy = authContext.id)
    }

  private def updateProject(project: Project): StepDBIO[Project] =
    (Logger[DBIO].info(s"successfully updated project ${project.id}") *>
      projectRepository
        .update(project))
      .valueOr(ProjectNotFound)

  private def checkUsersHaveAccessToOrganization(organization: Organization, request: ProjectUpdateRequest): StepDBIO[Unit] = {
    val projectUserIds = (request.ownerIds ++ request.memberIds).toSeq
    organizationUserRepository
      .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(projectUserIds)))
      .ensureF(
        _.length == projectUserIds.length,
        Logger[DBIO]
          .error(s"not all users have access to organization ${organization.id}")
          .as(ProjectUsersHaveNoAccessToOrganization)
      )
      .void
  }
  private def listProjectUsers(project: Project): StepDBIO[Set[ProjectUser]] =
    projectUserRepository.list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true)).seal.map(_.toSet)

  private def checkNotCreator(
    project: Project,
    projectUsers: Set[ProjectUser],
    toDeassign: Seq[ProjectUserId]
  ): StepDBIO[Unit] =
    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(
        _ =>
          !projectUsers
            .filter(projectUser => toDeassign.contains(projectUser.id))
            .map(_.userId)
            .contains(project.createdBy),
        Logger[DBIO]
          .error(s"Can not deassign creator from project ${project.id}")
          .as(CreatorNotDeassignable)
      )

  private def deassignUsers(project: Project, projectUserIds: Seq[ProjectUserId]): StepDBIO[Unit] = (Logger[DBIO].info(
    s"successfully deassigned project users for project ${project.id} in organization ${project.organizationId}"
  ) *> Clock[DBIO].instantNow.flatMap { now =>
    projectUserRepository.bulkArchiveAt(projectUserIds, ProjectUserArchivedAt(now))
  }: DBIO[Int]).seal.void

  private def prepareAssignProjectUsers(project: Project, projectUsersRoles: Seq[ProjectUserRole]): StepDBIO[Seq[ProjectUser]] =
    projectUsersRoles.traverse(prepareProjectUser(project, _))

  private def prepareProjectUser(project: Project, projectUserRole: ProjectUserRole): StepDBIO[ProjectUser] =
    (Clock[DBIO].instantNow.flatMap { now =>
      IdGenerator.nextId[DBIO, ProjectUserId].map { projectUserId =>
        ProjectUser(
          id = projectUserId,
          projectId = project.id,
          userId = projectUserRole.userId,
          role = projectUserRole.role,
          createdAt = ProjectUserCreatedAt(now),
          archivedAt = None
        )
      }
    }: DBIO[ProjectUser]).seal

  private def assignUsers(project: Project, projectUsers: Seq[ProjectUser]): StepDBIO[Unit] =
    (Logger[DBIO].info(
      s"successfully assigned project users for project ${project.id} in organization ${project.organizationId}"
    ) *> projectUserRepository.insert(projectUsers)).seal.void
}

object ProjectUpdateService {
  final case class ProjectUpdateRequest(
    name: Option[ProjectName],
    description: Option[ProjectDescription],
    ownerIds: Set[UserId],
    memberIds: Set[UserId])

  sealed trait ProjectUpdateResult
  object ProjectUpdateResult {
    final case class Updated(project: Project)               extends ProjectUpdateResult
    final case object NoAccessToOrganization                 extends ProjectUpdateResult
    final case object NotProjectOwner                        extends ProjectUpdateResult
    final case object OrganizationNotFound                   extends ProjectUpdateResult
    final case object ProjectNotFound                        extends ProjectUpdateResult
    final case object ProjectNameNotUnique                   extends ProjectUpdateResult
    final case object CreatorNotDeassignable                 extends ProjectUpdateResult
    final case object ProjectUsersHaveNoAccessToOrganization extends ProjectUpdateResult
  }
}
