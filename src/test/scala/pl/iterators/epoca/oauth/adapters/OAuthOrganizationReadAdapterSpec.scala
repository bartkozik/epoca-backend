package pl.iterators.epoca.oauth.adapters

import io.circe.syntax._
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationListService.OrganizationDto
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.PaginatedResult
import pl.iterators.epoca.utils.json.JsonProtocol._

class OAuthOrganizationReadAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator = allGenerators[Organization].normal

  "OAuthOrganizationReadAdapter" should {
    "serialize at most the bound organization independently of project selection" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val sut                    = new OAuthOrganizationReadAdapter(transactor, organizationRepository)
      val bound                  = organizationGenerator.generate.copy(archivedAt = None)
      val foreign                = organizationGenerator.generate.copy(archivedAt = None)
      val policy = OAuthAccessPolicy(
        bound.id,
        OAuthProjectAccess.SelectedProjects(Set(allGenerators[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId].normal.generate))
      )

      when(organizationRepository.list(any[OrganizationFilters])).thenReturn(Seq(bound, foreign).asDBIO)

      sut.list(policy, OrganizationFilters()).asserting { page =>
        val json = page.asJson.noSpaces
        (page shouldEqual PaginatedResult(1, Seq(OrganizationDto(bound, None)))) and
          (json must contain(bound.id.toString)) and
          (json must not(contain(foreign.id.toString)))
      }
    }

    "return an empty collection when an explicit organization filter is outside the binding" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val sut                    = new OAuthOrganizationReadAdapter(transactor, organizationRepository)
      val bound                  = organizationGenerator.generate.copy(archivedAt = None)
      val foreign                = organizationGenerator.generate.copy(archivedAt = None)
      val policy                 = OAuthAccessPolicy(bound.id, OAuthProjectAccess.AllProjects)

      sut.list(policy, OrganizationFilters(id = Some(foreign.id))).asserting(_ shouldEqual PaginatedResult(0, Seq.empty))
    }
  }
}
