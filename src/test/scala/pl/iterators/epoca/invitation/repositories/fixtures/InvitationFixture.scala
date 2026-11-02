package pl.iterators.epoca.invitation.repositories.fixtures

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.repositories.InvitationRepositoryImpl
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.utils.Generators
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait InvitationFixture {
  deps: Generators =>
  implicit val ec: ExecutionContext

  private val _dbioInvitationRepository: InvitationRepositoryImpl = new InvitationRepositoryImpl()

  private val _invitationGenerator = allGenerators[Invitation].normal
  def withInvitation(organization: Organization, obj: Invitation = _invitationGenerator.generate): DBIO[Invitation] =
    _dbioInvitationRepository.insert(obj.copy(organizationId = organization.id))
}
