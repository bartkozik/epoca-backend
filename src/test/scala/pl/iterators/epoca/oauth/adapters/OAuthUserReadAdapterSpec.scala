package pl.iterators.epoca.oauth.adapters

import io.circe.syntax._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.oauth.adapters.OAuthReadResult.Ok
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.OrganizationUserDetailsRow
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.OrganizationUserDto
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol._

class OAuthUserReadAdapterSpec extends ServiceSpec {
  private lazy val organizationGenerator     = allGenerators[Organization].normal
  private lazy val organizationUserGenerator = allGenerators[OrganizationUser].normal
  private lazy val projectGenerator          = allGenerators[Project].normal
  private lazy val projectUserGenerator      = allGenerators[ProjectUser].normal
  private lazy val clientGenerator           = allGenerators[Client].normal
  private lazy val userGenerator             = allGenerators[User].normal

  "OAuthUserReadAdapter" should {
    "serialize only human members assigned to policy-visible projects" in new BaseScope {
      val organizationRepository     = mock[OrganizationRepository]
      val organizationUserRepository = mock[OrganizationUserRepository]
      val projectRepository          = mock[ProjectRepository]
      val projectUserRepository      = mock[ProjectUserRepository]
      val clientRepository           = mock[ClientRepository]
      val userRepository             = mock[UserRepository]
      val userIdentityRepository     = mock[UserIdentityRepository]
      val sut = new OAuthUserReadAdapter(
        transactor,
        organizationRepository,
        organizationUserRepository,
        projectRepository,
        projectUserRepository,
        clientRepository,
        userRepository,
        userIdentityRepository
      )

      val organization   = organizationGenerator.generate.copy(archivedAt = None)
      val client         = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1             = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val p2             = projectGenerator.generate.copy(organizationId = organization.id, clientId = client.id, archivedAt = None)
      val humanP1        = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val humanP2        = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val serviceAccount = userGenerator.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val projectUsers = Seq(
        projectUserGenerator.generate.copy(projectId = p1.id, userId = humanP1.id, archivedAt = None),
        projectUserGenerator.generate.copy(projectId = p1.id, userId = serviceAccount.id, archivedAt = None),
        projectUserGenerator.generate.copy(projectId = p2.id, userId = humanP2.id, archivedAt = None)
      )
      val organizationUsers = Seq(humanP1, humanP2, serviceAccount).map(user =>
        organizationUserGenerator.generate.copy(organizationId = organization.id, userId = user.id, archivedAt = None)
      )
      val policy = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any[ProjectRepository.ProjectFilters])).thenReturn(Seq(p1, p2).asDBIO)
      when(projectUserRepository.list(any[ProjectUserRepository.ProjectUserFilters])).thenReturn(projectUsers.asDBIO)
      when(organizationUserRepository.list(any[OrganizationUserRepository.OrganizationUserFilters])).thenReturn(organizationUsers.asDBIO)
      when(userRepository.list(any[UserRepository.UserFilters])).thenReturn(Seq(humanP1, serviceAccount, humanP2).asDBIO)
      when(userIdentityRepository.list(any[UserIdentityRepository.UserIdentityFilters])).thenReturn(Seq.empty.asDBIO)

      sut.list(policy, organization.slug).asserting { result =>
        val users = result.asInstanceOf[Ok[Seq[OrganizationUserDto]]].value
        val json  = users.asJson.noSpaces

        (users.map(_.id) shouldEqual Seq(humanP1.id)) and
          (json must contain(humanP1.id.toString)) and
          (json must not(contain(humanP2.id.toString))) and
          (json must not(contain(serviceAccount.id.toString))) and
          (json must not(contain(p2.id.toString)))
      }
    }

    "serialize user details only for visible project rows" in new BaseScope {
      val organizationRepository     = mock[OrganizationRepository]
      val organizationUserRepository = mock[OrganizationUserRepository]
      val projectRepository          = mock[ProjectRepository]
      val projectUserRepository      = mock[ProjectUserRepository]
      val clientRepository           = mock[ClientRepository]
      val userRepository             = mock[UserRepository]
      val userIdentityRepository     = mock[UserIdentityRepository]
      val sut = new OAuthUserReadAdapter(
        transactor,
        organizationRepository,
        organizationUserRepository,
        projectRepository,
        projectUserRepository,
        clientRepository,
        userRepository,
        userIdentityRepository
      )

      val organization = organizationGenerator.generate.copy(archivedAt = None)
      val c1           = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val c2           = clientGenerator.generate.copy(organizationId = organization.id, archivedAt = None)
      val p1           = projectGenerator.generate.copy(organizationId = organization.id, clientId = c1.id, archivedAt = None)
      val p2           = projectGenerator.generate.copy(organizationId = organization.id, clientId = c2.id, archivedAt = None)
      val human        = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val membership   = organizationUserGenerator.generate.copy(organizationId = organization.id, userId = human.id, archivedAt = None)
      val p1Membership = projectUserGenerator.generate.copy(projectId = p1.id, userId = human.id, archivedAt = None)
      val p2Membership = projectUserGenerator.generate.copy(projectId = p2.id, userId = human.id, archivedAt = None)
      val policy       = OAuthAccessPolicy(organization.id, OAuthProjectAccess.SelectedProjects(Set(p1.id)))

      when(organizationRepository.find(any[OrganizationRepository.OrganizationFilters])).thenReturn(Some(organization).asDBIO)
      when(projectRepository.list(any[ProjectRepository.ProjectFilters])).thenReturn(Seq(p1, p2).asDBIO)
      when(projectUserRepository.list(any[ProjectUserRepository.ProjectUserFilters])).thenReturn(Seq(p1Membership, p2Membership).asDBIO)
      when(organizationUserRepository.list(any[OrganizationUserRepository.OrganizationUserFilters])).thenReturn(Seq(membership).asDBIO)
      when(userRepository.list(any[UserRepository.UserFilters])).thenReturn(Seq(human).asDBIO)
      when(clientRepository.list(any[ClientRepository.ClientFilters])).thenReturn(Seq(c1, c2).asDBIO)

      sut.details(policy, organization.slug, human.id).asserting { result =>
        val rows = result.asInstanceOf[Ok[Seq[OrganizationUserDetailsRow]]].value
        val json = rows.asJson.noSpaces

        (rows.map(_.projectId) shouldEqual Seq(p1.id)) and
          (json must contain(p1.id.toString)) and
          (json must contain(c1.id.toString)) and
          (json must not(contain(p2.id.toString))) and
          (json must not(contain(c2.id.toString)))
      }
    }
  }
}
