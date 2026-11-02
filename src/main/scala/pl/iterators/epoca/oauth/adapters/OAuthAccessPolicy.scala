package pl.iterators.epoca.oauth.adapters

import pl.iterators.epoca.oauth.domain.OAuthAuthContext
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId

final case class OAuthAccessPolicy(organizationId: OrganizationId, projectAccess: OAuthProjectAccess) {
  def allows(projectId: ProjectId): Boolean = projectAccess match {
    case OAuthProjectAccess.AllProjects                  => true
    case OAuthProjectAccess.SelectedProjects(projectIds) => projectIds.contains(projectId)
  }
}

object OAuthAccessPolicy {
  def from(auth: OAuthAuthContext): OAuthAccessPolicy = OAuthAccessPolicy(auth.organizationId, auth.projectAccess)
}

sealed trait OAuthReadResult[+A]
object OAuthReadResult {
  final case class Ok[A](value: A) extends OAuthReadResult[A]
  case object NotFound             extends OAuthReadResult[Nothing]
}
