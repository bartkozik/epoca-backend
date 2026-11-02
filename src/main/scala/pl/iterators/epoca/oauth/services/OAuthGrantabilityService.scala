package pl.iterators.epoca.oauth.services

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.oauth.services.OAuthGrantabilityService.{OAuthGrantability, OAuthGrantabilityError}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.BaseRepository.driver.api.DBIO

import scala.concurrent.ExecutionContext

final class OAuthGrantabilityService(
  userRepository: UserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository,
  projectRepository: ProjectRepository
)(implicit ec: ExecutionContext) {

  def resolve(auth: AuthContext, organizationSlug: OrganizationSlug): DBIO[Either[OAuthGrantabilityError, OAuthGrantability]] =
    organizationRepository.find(OrganizationFilters(slug = Some(organizationSlug))).flatMap {
      case None => DBIO.successful(Left(OAuthGrantabilityError.OrganizationNotFound))
      case Some(organization) =>
        userRepository.find(UserFilters(id = Some(auth.id))).flatMap {
          case None                              => DBIO.successful(Left(OAuthGrantabilityError.OperationNotPermitted))
          case Some(actor) if actor.isSuperAdmin => grantAll(organization).map(Right.apply)
          case Some(_) =>
            organizationUserRepository
              .find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(auth.id)))
              .flatMap {
                case None => DBIO.successful(Left(OAuthGrantabilityError.OrganizationNotFound))
                case Some(membership) if membership.roles.contains(OrganizationUserRole.Owner) => grantAll(organization).map(Right.apply)
                case Some(_) => DBIO.successful(Left(OAuthGrantabilityError.OperationNotPermitted))
              }
        }
    }

  def validateDelegation(
    grantability: OAuthGrantability,
    scopes: Set[OAuthScope],
    projectAccess: OAuthProjectAccess
  ): Either[OAuthGrantabilityError, Unit] =
    if (scopes.isEmpty) Left(OAuthGrantabilityError.InvalidPolicy)
    else if (!scopes.subsetOf(grantability.scopes)) Left(OAuthGrantabilityError.OperationNotPermitted)
    else
      projectAccess match {
        case OAuthProjectAccess.AllProjects =>
          Either.cond(grantability.mayGrantAllProjects, (), OAuthGrantabilityError.OperationNotPermitted)
        case OAuthProjectAccess.SelectedProjects(projectIds) if projectIds.isEmpty =>
          Left(OAuthGrantabilityError.InvalidPolicy)
        case OAuthProjectAccess.SelectedProjects(projectIds) =>
          Either.cond(projectIds.subsetOf(grantability.accessibleProjectIds), (), OAuthGrantabilityError.ProjectNotFound)
      }

  private def grantAll(organization: Organization): DBIO[OAuthGrantability] =
    projectRepository
      .list(ProjectFilters(organizationIds = Some(Seq(organization.id)), includeArchived = true))
      .map(projects => OAuthGrantability(organization, OAuthScope.values.toSet, projects.map(_.id).toSet, mayGrantAllProjects = true))
}

object OAuthGrantabilityService {
  final case class OAuthGrantability(
    organization: Organization,
    scopes: Set[OAuthScope],
    accessibleProjectIds: Set[ProjectId],
    mayGrantAllProjects: Boolean)

  sealed trait OAuthGrantabilityError
  object OAuthGrantabilityError {
    case object OrganizationNotFound  extends OAuthGrantabilityError
    case object OperationNotPermitted extends OAuthGrantabilityError
    case object InvalidPolicy         extends OAuthGrantabilityError
    case object ProjectNotFound       extends OAuthGrantabilityError
  }
}
