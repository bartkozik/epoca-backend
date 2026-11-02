package pl.iterators.epoca.client.repositories.fixtures

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait ClientFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioClientRepository: ClientRepositoryImpl = new ClientRepositoryImpl()

  private val _clientGenerator = allGenerators[Client].normal
  def withClient(organization: Organization, obj: Client = _clientGenerator.generate): DBIO[Client] =
    _dbioClientRepository.insert(obj.copy(organizationId = organization.id))
}
