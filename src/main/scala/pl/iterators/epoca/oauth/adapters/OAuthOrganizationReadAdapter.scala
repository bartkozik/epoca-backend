package pl.iterators.epoca.oauth.adapters

import cats.effect.IO
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationListService.OrganizationDto
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}

import scala.concurrent.ExecutionContext

trait OAuthOrganizationReader {
  def list(policy: OAuthAccessPolicy, filters: OrganizationFilters): IO[PaginatedResult[OrganizationDto]]
  def find(policy: OAuthAccessPolicy, slug: OrganizationSlug): IO[Option[OrganizationDto]]
}

final class OAuthOrganizationReadAdapter(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository
)(implicit ec: ExecutionContext)
    extends OAuthOrganizationReader {

  def list(policy: OAuthAccessPolicy, filters: OrganizationFilters): IO[PaginatedResult[OrganizationDto]] =
    if (!matchesBinding(policy, filters)) IO.pure(PaginatedResult(0, Seq.empty))
    else
      transactor.execute {
        organizationRepository
          .list(filters.copy(id = Some(policy.organizationId), ids = None, userId = None, creatorId = None, pageSizeAndNumber = None))
          .map { organizations =>
            val dtos = organizations
              .filter(_.id == policy.organizationId)
              .map(organization => OrganizationDto(organization, roles = None))
            val page = filters.pageSizeAndNumber.fold(dtos)(value => dtos.slice(value.offset, value.offset + value.size))
            PaginatedResult(dtos.size, page)
          }
      }

  def find(policy: OAuthAccessPolicy, slug: OrganizationSlug): IO[Option[OrganizationDto]] =
    transactor.execute {
      organizationRepository
        .find(OrganizationFilters(id = Some(policy.organizationId), slug = Some(slug), includeArchived = true))
        .map(_.filter(organization => organization.id == policy.organizationId && organization.slug == slug).map(OrganizationDto(_, None)))
    }

  private def matchesBinding(policy: OAuthAccessPolicy, filters: OrganizationFilters): Boolean =
    filters.id.forall(_ == policy.organizationId) &&
      filters.ids.forall(_.contains(policy.organizationId))
}
