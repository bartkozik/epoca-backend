package pl.iterators.epoca.organization.repositories.fixtures

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepositoryImpl
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait OrganizationFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioOrganizationRepository: OrganizationRepositoryImpl = new OrganizationRepositoryImpl()

  private val _organizationGenerator = allGenerators[Organization].normal
  def withOrganization(obj: Organization = _organizationGenerator.generate): DBIO[Organization] =
    _dbioOrganizationRepository.insert(obj)
}
