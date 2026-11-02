package pl.iterators.epoca.organization.repositories.fixtures

import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OrganizationUserFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioOrganizationUserRepository: OrganizationUserRepositoryImpl = new OrganizationUserRepositoryImpl()

  private val _organizationUserGenerator = allGenerators[OrganizationUser].normal
  def withOrganizationUser(obj: OrganizationUser = _organizationUserGenerator.generate): DBIO[OrganizationUser] =
    _dbioOrganizationUserRepository.insert(obj)

  def withCreatorOrganizationUser(organization: Organization, roles: Seq[OrganizationUserRole]): DBIO[OrganizationUser] =
    withOrganizationUser(OrganizationUser(OrganizationUserId.random, organization.id, organization.createdBy, roles, None))
}
