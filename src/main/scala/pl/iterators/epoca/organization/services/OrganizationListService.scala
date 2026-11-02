package pl.iterators.epoca.organization.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.services.SamlSessionScope
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationListService.{OrganizationDto, OrganizationListResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{PaginatedResult, PostgresIOTransactor}
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.sealedmonad.syntax._

class OrganizationListService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationListResult](ioRuntime)
    with LoggingSupport {

  def list(authContext: AuthContext, filter: OrganizationFilters): IO[OrganizationListResult] = {
    val scope = SamlSessionScope.binding(authContext).map(_.organizationId)
    if ((SamlSessionScope.required(authContext) && scope.isEmpty) || scope.exists(id => filter.id.exists(_ != id)))
      return IO.pure(OrganizationListResult.Ok(PaginatedResult(0, Nil)))
    val preparedFilter = prepareFilter(SamlSessionScope.restrict(authContext), filter.copy(id = scope.orElse(filter.id)))
    transactor.executeTransactionally(IsolationLevel.RepeatableRead) {
      (for {
        organizations     <- listOrganizations(preparedFilter)
        count             <- countOrganizations(preparedFilter)
        organizationUsers <- listOrganizationsUsers(authContext, organizations)
        dto = prepareResponse(organizations, organizationUsers)
      } yield OrganizationListResult.Ok(PaginatedResult(count, dto))).run
    }
  }

  private def prepareFilter(authContext: AuthContext, filter: OrganizationFilters): OrganizationFilters =
    filter.copy(userId = Option.when(!authContext.isAdmin)(authContext.id))

  private def listOrganizations(filter: OrganizationFilters): StepDBIO[Seq[Organization]] =
    organizationRepository.list(filter).ensure(_.nonEmpty, OrganizationListResult.Ok(PaginatedResult(0, Nil)))

  private def countOrganizations(filter: OrganizationFilters): StepDBIO[Int] =
    organizationRepository.count(filter).seal

  private def listOrganizationsUsers(authContext: AuthContext, organizations: Seq[Organization]): StepDBIO[Seq[OrganizationUser]] =
    organizationUserRepository.list(OrganizationUserFilters(organizationIds = Some(organizations.map(_.id)), userId = Some(authContext.id))).seal

  private def prepareResponse(organizations: Seq[Organization], organizationUsers: Seq[OrganizationUser]): Seq[OrganizationDto] = {
    val orgUserMap: Map[OrganizationId, Seq[OrganizationUserRole]] = organizationUsers.groupBy(_.organizationId).map { case (id, list) =>
      id -> list.flatMap(_.roles)
    }
    organizations.map(o => OrganizationDto(o, orgUserMap.get(o.id)))
  }
}

object OrganizationListService {
  final case class OrganizationDto(
    id: OrganizationId,
    name: OrganizationName,
    slug: OrganizationSlug,
    roles: Option[Seq[OrganizationUserRole]],
    createdAt: OrganizationCreatedAt,
    createdBy: UserId,
    modifiedAt: OrganizationModifiedAt,
    modifiedBy: UserId,
    archivedAt: Option[OrganizationArchivedAt])

  object OrganizationDto {
    def apply(organization: Organization, roles: Option[Seq[OrganizationUserRole]]): OrganizationDto =
      organization.into[OrganizationDto].withFieldConst(_.roles, roles).transform
  }

  sealed trait OrganizationListResult
  object OrganizationListResult {
    final case class Ok(organizations: PaginatedResult[OrganizationDto]) extends OrganizationListResult
  }
}
