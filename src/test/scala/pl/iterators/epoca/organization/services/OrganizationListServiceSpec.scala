package pl.iterators.epoca.organization.services

import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationListService.{OrganizationDto, OrganizationListResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.utils.transactor.{IsolationLevel, TransactorCall}

class OrganizationListServiceSpec extends ServiceSpec {
  lazy val organizationUserGenerator: AllGenerators[OrganizationUser] = allGenerators[OrganizationUser]
  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]

    val sut: OrganizationListService = wire[OrganizationListService]

    val organization: Organization         = organizationGenerator.normal.generate.copy(archivedAt = None)
    val authOrganization: Organization     = organizationGenerator.normal.generate.copy(archivedAt = None, createdBy = authUser.id)
    val organizationUser: OrganizationUser = organizationUserGenerator.maximal.generate.copy(organizationId = organization.id, userId = authUser.id)
  }

  "list" should {
    "never list another organization through SAML scope even for a super-admin account" in new TestCase {
      val proof = pl.iterators.epoca.auth.domain.AccountAuthentication(
        java.time.Instant.now(),
        pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml(authOrganization.id, 1L, "issuer", "subject")
      )
      val scoped = authUser.copy(isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(true), accountAuthentication = Some(proof))
      when(organizationRepository.list(any)).thenReturn(Seq(organization).asDBIO)
      when(organizationRepository.count(any)).thenReturn(1.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(organizationUser).asDBIO)
      sut.list(scoped, OrganizationFilters(id = Some(organization.id))).asserting(_ shouldEqual OrganizationListResult.Ok(PaginatedResult(0, Nil)))
    }
    "return Ok and do not ash for any other if repo list method return empty result)" in new TestCase {
      val auth = authUser.copy(identities = Nil)
      when(organizationRepository.list(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(Nil.asDBIO)
      sut
        .list(auth, OrganizationFilters())
        .asserting(
          _ shouldEqual OrganizationListResult
            .Ok(PaginatedResult(0, Nil))
        )

      verify(organizationRepository).list(OrganizationFilters(userId = Some(authUser.id)))
    }

    "return Ok with all organizations which user belongs to (no identities in auth)" in new TestCase {
      val auth = authUser.copy(identities = Nil)
      when(organizationRepository.list(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(Seq(organization, authOrganization).asDBIO)
      when(organizationRepository.count(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(100.asDBIO)
      when(
        organizationUserRepository
          .list(OrganizationUserFilters(organizationIds = Some(Seq(organization, authOrganization).map(_.id)), userId = Some(authUser.id)))
      ).thenReturn(Seq(organizationUser).asDBIO)
      sut
        .list(auth, OrganizationFilters())
        .asserting(
          _ shouldEqual OrganizationListResult
            .Ok(PaginatedResult(100, Seq(OrganizationDto(organization, Some(organizationUser.roles)), OrganizationDto(authOrganization, None))))
        )

      verify(organizationRepository).list(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationRepository).count(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationUserRepository).list(
        OrganizationUserFilters(organizationIds = Some(Seq(organization, authOrganization).map(_.id)), userId = Some(authUser.id))
      )
    }

    "read the page and the count in a single RepeatableRead transaction" in new TestCase {
      when(organizationRepository.list(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(Seq(organization).asDBIO)
      when(organizationRepository.count(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(100.asDBIO)
      when(
        organizationUserRepository
          .list(OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authUser.id)))
      ).thenReturn(Seq(organizationUser).asDBIO)

      sut
        .list(authUser, OrganizationFilters())
        .asserting(_ => transactorCalls shouldEqual Seq(TransactorCall.ExecuteTransactionallyAt(IsolationLevel.RepeatableRead)))

      verify(organizationRepository).list(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationRepository).count(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationUserRepository).list(
        OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authUser.id))
      )
    }

    "return Ok with all organizations which user belongs" in new TestCase {
      when(organizationRepository.list(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(Seq(organization).asDBIO)
      when(organizationRepository.count(OrganizationFilters(userId = Some(authUser.id)))).thenReturn(100.asDBIO)
      when(
        organizationUserRepository
          .list(OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authUser.id)))
      ).thenReturn(Seq(organizationUser).asDBIO)
      sut
        .list(authUser, OrganizationFilters())
        .asserting(_ shouldEqual OrganizationListResult.Ok(PaginatedResult(100, Seq(OrganizationDto(organization, Some(organizationUser.roles))))))

      verify(organizationRepository).list(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationRepository).count(OrganizationFilters(userId = Some(authUser.id)))
      verify(organizationUserRepository).list(
        OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authUser.id))
      )
    }

    "return Ok with all organizations which user belongs to and including filters (no email connection)" in new TestCase {
      val fullFilter: OrganizationFilters = OrganizationFilters(
        id = Some(authOrganization.id),
        userId = Some(authAdmin.id),
        creatorId = Some(authUser.id),
        includeArchived = true,
        pageSizeAndNumber = PageSizeAndNumber.fromOptions(Some(1), Some(1))
      )

      val fullFilterWithSetUserId: OrganizationFilters = fullFilter.copy(userId = Some(authUser.id))

      when(organizationRepository.list(fullFilterWithSetUserId)).thenReturn(Seq(authOrganization).asDBIO)
      when(organizationRepository.count(fullFilterWithSetUserId)).thenReturn(100.asDBIO)
      when(
        organizationUserRepository
          .list(OrganizationUserFilters(organizationIds = Some(Seq(authOrganization).map(_.id)), userId = Some(authUser.id)))
      ).thenReturn(Seq(organizationUser).asDBIO)
      sut
        .list(authUser, fullFilter)
        .asserting(_ shouldEqual OrganizationListResult.Ok(PaginatedResult(100, Seq(OrganizationDto(authOrganization, None)))))

      verify(organizationRepository).list(fullFilterWithSetUserId)
      verify(organizationRepository).count(fullFilterWithSetUserId)
      verify(organizationUserRepository).list(
        OrganizationUserFilters(organizationIds = Some(Seq(authOrganization).map(_.id)), userId = Some(authUser.id))
      )
    }
    "return Ok with all organizations for admin" in new TestCase {
      when(organizationRepository.list(OrganizationFilters())).thenReturn(Seq(organization).asDBIO)
      when(organizationRepository.count(OrganizationFilters())).thenReturn(100.asDBIO)
      when(
        organizationUserRepository
          .list(OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authAdmin.id)))
      ).thenReturn(Nil.asDBIO)
      sut
        .list(authAdmin, OrganizationFilters())
        .asserting(_ shouldEqual OrganizationListResult.Ok(PaginatedResult(100, Seq(OrganizationDto(organization, None)))))

      verify(organizationRepository).list(OrganizationFilters())
      verify(organizationRepository).count(OrganizationFilters())
      verify(organizationUserRepository).list(
        OrganizationUserFilters(organizationIds = Some(Seq(organization).map(_.id)), userId = Some(authAdmin.id))
      )
    }
  }

  "OrganizationDto" should {
    "copy every organization value and attach the supplied roles" in new TestCase {
      OrganizationDto(organization, Some(organizationUser.roles)) shouldEqual OrganizationDto(
        id = organization.id,
        name = organization.name,
        slug = organization.slug,
        roles = Some(organizationUser.roles),
        createdAt = organization.createdAt,
        createdBy = organization.createdBy,
        modifiedAt = organization.modifiedAt,
        modifiedBy = organization.modifiedBy,
        archivedAt = organization.archivedAt
      )
    }
  }
}
