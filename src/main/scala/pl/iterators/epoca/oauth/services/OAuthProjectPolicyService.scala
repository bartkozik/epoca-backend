package pl.iterators.epoca.oauth.services

import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess.{AllProjects, SelectedProjects}
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthProjectAccess, OAuthProjectAccessMode, OAuthProjectPolicyError}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId

object OAuthProjectPolicyService {
  def normalize(mode: OAuthProjectAccessMode, projectIds: Set[ProjectId]): Either[OAuthProjectPolicyError, OAuthProjectAccess] =
    mode match {
      case OAuthProjectAccessMode.AllProjects =>
        Either.cond(projectIds.isEmpty, AllProjects, OAuthProjectPolicyError.AllProjectsMustNotHaveProjectIds)
      case OAuthProjectAccessMode.SelectedProjects =>
        Either.cond(projectIds.nonEmpty, SelectedProjects(projectIds), OAuthProjectPolicyError.SelectedProjectsMustNotBeEmpty)
    }

  def allows(access: OAuthProjectAccess, projectId: ProjectId): Boolean =
    access match {
      case AllProjects                  => true
      case SelectedProjects(projectIds) => projectIds.contains(projectId)
    }

  def intersect(access: OAuthProjectAccess, candidates: Set[ProjectId]): Set[ProjectId] =
    access match {
      case AllProjects                  => candidates
      case SelectedProjects(projectIds) => candidates.intersect(projectIds)
    }
}
