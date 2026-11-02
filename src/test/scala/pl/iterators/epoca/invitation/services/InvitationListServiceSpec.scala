package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.repositories.InvitationRepository
import pl.iterators.epoca.invitation.services.InvitationListService.InvitationListResult
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.transactor.{IsolationLevel, TransactorCall}

class InvitationListServiceSpec extends ServiceSpec {
  lazy val invitationGenerator: AllGenerators[Invitation] = allGenerators[Invitation]

  trait TestCase extends BaseScope {
    val invitationRepository: InvitationRepository = vmock[InvitationRepository]

    val sut: InvitationListService = wire[InvitationListService]

    val invitation: Invitation = invitationGenerator.normal.generate.copy(archivedAt = None)
  }

  "list" should {
    "return Ok with the user's invitations and their count" in new TestCase {
      when(invitationRepository.list(any)).thenReturn(Seq(invitation).asDBIO)
      when(invitationRepository.count(any)).thenReturn(7.asDBIO)

      sut.list(authUser).asserting(_ shouldEqual InvitationListResult.Ok(PaginatedResult(7, Seq(invitation))))

      verify(invitationRepository).list(any)
      verify(invitationRepository).count(any)
    }

    "read the page and the count in a single RepeatableRead transaction" in new TestCase {
      when(invitationRepository.list(any)).thenReturn(Seq(invitation).asDBIO)
      when(invitationRepository.count(any)).thenReturn(7.asDBIO)

      sut
        .list(authUser)
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(invitationRepository).list(any)
      verify(invitationRepository).count(any)
    }
  }
}
