package pl.iterators.epoca.oauth.adapters

import io.circe.syntax._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.services.ClientDtoService.ClientDto
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.Ok
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.utils.json.JsonProtocol._

class OAuthClientReadAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator = allGenerators[Organization].normal
  private lazy val clientGenerator       = allGenerators[Client].normal
  private lazy val projectGenerator      = allGenerators[Project].normal

  "OAuthClientReadAdapter" should {
    "serialize only clients connected to visible projects and count only those projects" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val clientRepository       = mock[ClientRepository]
      val projectRepository      = mock[ProjectRepository]
      val sut                    = new OAuthClientReadAdapter(transactor, organizationRepository, clientRepository, projectRepository)

      val organization  = organizationGenerator.generate.copy(archivedAt = None)
      val c1            = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val c2            = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1            = projectGenerator.generate.copy(organizationId = organization.id, clientId = c1.id, archivedAt = None)
      val p2            = projectGenerator.generate.copy(organizationId = organization.id, clientId = c2.id, archivedAt = None)
      val policy        = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))
      val requestedPage = PageSizeAndNumber(size = 1, number = 1)
      val boundedFilter = ClientRepository.ClientFilters(
        organizationIds = Some(Seq(organization.id)),
        pageSizeAndNumber = Some(requestedPage),
        projectIds = Some(Seq(p1.id)),
        requireActiveProject = true
      )
      val pageProjectFilter =
        ProjectRepository.ProjectFilters(ids = Some(Seq(p1.id)), organizationIds = Some(Seq(organization.id)), clientIds = Some(Seq(c1.id, c2.id)))

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any[ProjectRepository.ProjectFilters])).thenReturn(Seq(p1, p2).asDBIO)
      when(clientRepository.list(any[ClientRepository.ClientFilters])).thenReturn(Seq(c1, c2).asDBIO)
      when(clientRepository.count(any[ClientRepository.ClientFilters])).thenReturn(1.asDBIO)

      sut.list(policy, organization.slug, ClientRepository.ClientFilters(pageSizeAndNumber = Some(requestedPage))).asserting { result =>
        val page = result.asInstanceOf[Ok[PaginatedResult[ClientDto]]].value
        val json = page.asJson.noSpaces

        verify(clientRepository).list(boundedFilter)
        verify(clientRepository).count(boundedFilter)
        verify(projectRepository).list(pageProjectFilter)

        (page.count shouldEqual 1) and
          (page.results.map(_.id) shouldEqual Seq(c1.id)) and
          (page.results.head.projectCount shouldEqual pl.iterators.epoca.client.domain.ClientDomain.ClientProjectCount(1)) and
          (json must contain(c1.id.toString)) and
          (json must not(contain(c2.id.toString)))
      }
    }
  }
}
