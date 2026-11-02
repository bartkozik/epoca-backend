package pl.iterators.epoca.oauth.adapters

import io.circe.syntax._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.{NotFound, Ok}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.utils.json.JsonProtocol._

class OAuthProjectReadAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator = allGenerators[Organization].normal
  private lazy val projectGenerator      = allGenerators[Project].normal
  private lazy val projectUserGenerator  = allGenerators[ProjectUser].normal
  private lazy val clientGenerator       = allGenerators[Client].normal
  private lazy val userGenerator         = allGenerators[User].normal

  "OAuthProjectReadAdapter" should {
    "serialize only policy-visible projects and human nested users" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val projectUserRepository  = mock[ProjectUserRepository]
      val clientRepository       = mock[ClientRepository]
      val userRepository         = mock[UserRepository]
      val sut =
        new OAuthProjectReadAdapter(transactor, organizationRepository, projectRepository, projectUserRepository, clientRepository, userRepository)

      val organization   = organizationGenerator.generate.copy(archivedAt = None)
      val client         = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1             = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val p2             = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val human          = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val serviceAccount = userGenerator.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val memberships = Seq(
        projectUserGenerator.generate.copy(projectId = p1.id, userId = human.id, archivedAt = None),
        projectUserGenerator.generate.copy(projectId = p1.id, userId = serviceAccount.id, archivedAt = None),
        projectUserGenerator.generate.copy(projectId = p2.id, userId = human.id, archivedAt = None)
      )
      val policy        = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))
      val requestedPage = PageSizeAndNumber(size = 1, number = 1)
      val boundedFilter = ProjectRepository.ProjectFilters(
        ids = Some(Seq(p1.id)),
        organizationIds = Some(Seq(organization.id)),
        pageSizeAndNumber = Some(requestedPage)
      )

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any[ProjectRepository.ProjectFilters])).thenReturn(Seq(p1, p2).asDBIO)
      when(projectRepository.count(any[ProjectRepository.ProjectFilters])).thenReturn(1.asDBIO)
      when(clientRepository.list(any[ClientRepository.ClientFilters])).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(any[ProjectUserRepository.ProjectUserFilters])).thenReturn(memberships.asDBIO)
      when(userRepository.list(any[UserRepository.UserFilters])).thenReturn(Seq(human).asDBIO)

      sut.list(policy, organization.slug, ProjectRepository.ProjectFilters(pageSizeAndNumber = Some(requestedPage))).asserting { result =>
        val page = result.asInstanceOf[Ok[PaginatedResult[pl.iterators.epoca.project.services.ProjectDtoService.ProjectListDto]]].value
        val json = page.asJson.noSpaces

        verify(projectRepository).list(boundedFilter)
        verify(projectRepository).count(boundedFilter)

        (page.count shouldEqual 1) and
          (page.results.map(_.id) shouldEqual Seq(p1.id)) and
          (page.results.head.userIds shouldEqual Seq(human.id)) and
          (page.results.head.userCount shouldEqual pl.iterators.epoca.project.domain.ProjectDomain.ProjectUserCount(1)) and
          (json must contain(p1.id.toString)) and
          (json must not(contain(p2.id.toString))) and
          (json must not(contain(serviceAccount.id.toString)))
      }
    }

    "return NotFound for a direct project outside the policy even if a repository returns it" in new BaseScope {
      val organizationRepository = mock[OrganizationRepository]
      val projectRepository      = mock[ProjectRepository]
      val projectUserRepository  = mock[ProjectUserRepository]
      val clientRepository       = mock[ClientRepository]
      val userRepository         = mock[UserRepository]
      val sut =
        new OAuthProjectReadAdapter(transactor, organizationRepository, projectRepository, projectUserRepository, clientRepository, userRepository)

      val organization = organizationGenerator.generate.copy(archivedAt = None)
      val client       = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1           = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val p2           = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any[ProjectRepository.ProjectFilters])).thenReturn(Some(p2).asDBIO)

      sut.find(policy, organization.slug, p2.id).asserting(_ shouldEqual NotFound)
    }
  }
}
