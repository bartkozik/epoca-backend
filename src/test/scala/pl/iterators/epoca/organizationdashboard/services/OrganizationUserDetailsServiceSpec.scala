package pl.iterators.epoca.organizationdashboard.services

import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserDetailsService.{OrganizationUserDetailsResult, OrganizationUserDetailsRow}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.utils.ServiceSpec

class OrganizationUserDetailsServiceSpec extends ServiceSpec {
  lazy val organizationGenerator: AllGenerators[Organization]         = allGenerators[Organization]
  lazy val projectGenerator: AllGenerators[Project]                   = allGenerators[Project]
  lazy val clientGenerator: AllGenerators[Client]                     = allGenerators[Client]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]           = allGenerators[ProjectUser]
  lazy val organizationUserGenerator: AllGenerators[OrganizationUser] = allGenerators[OrganizationUser]
  lazy val userGenerator: AllGenerators[User]                         = allGenerators[User]
  lazy val userIdGenerator: AllGenerators[UserId]                     = allGenerators[UserId]

  trait TestCase extends BaseScope {
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val clientRepository: ClientRepository                     = vmock[ClientRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]

    val sut: OrganizationUserDetailsService = wire[OrganizationUserDetailsService]
  }

  "details" should {
    "return OrganizationNotFound when organization does not exist" in new TestCase {
      val organization = organizationGenerator.normal.generate
      val userId       = userIdGenerator.normal.generate

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))

      when(organizationRepository.find(organizationFilter)).thenReturn(None.asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.OrganizationNotFound)

      verify(organizationRepository).find(organizationFilter)
    }
    "return OrganizationNoAccess when requester only has a management role in another organization" in new TestCase {
      val userId            = userIdGenerator.normal.generate
      val organization      = organizationGenerator.normal.generate.copy(createdBy = userId)
      val otherOrganization = organizationGenerator.normal.generate
      val requester         = authUser.copy(roles = Seq(AuthContext.OrganizationRoles(otherOrganization.id, Seq(OrganizationUserRole.Manager))))

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(requester.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(None.asDBIO)

      sut.details(requester, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.OrganizationNoAccess)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
    }
    "propagate requester membership lookup failures" in new TestCase {
      val userId       = userIdGenerator.normal.generate
      val organization = organizationGenerator.normal.generate.copy(createdBy = userId)
      val expected     = new RuntimeException("requester membership lookup failed")

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(slick.dbio.DBIO.failed(expected))

      sut.details(authUser, organization.slug, userId).attempt.asserting(_ shouldEqual Left(expected))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
    }
    "return NotAMember if target user isn't a member of organization" in new TestCase {
      val organization = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val userId       = userIdGenerator.normal.generate

      val organizationFilter     = OrganizationFilters(slug = Some(organization.slug))
      val organizationUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter)).thenReturn(None.asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.NotAMember)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(organizationUserFilter)
    }
    "return the same NotAMember result for service account details" in new TestCase {
      val organization   = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val targetFilter       = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(targetFilter)).thenReturn(None.asDBIO)

      sut.details(authUser, organization.slug, serviceAccount.id).asserting(_ shouldEqual OrganizationUserDetailsResult.NotAMember)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(targetFilter)
    }
    "return Ok with service data if users are asking about themselves" in new TestCase {
      val organization     = organizationGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate
      val client           = clientGenerator.normal.generate
      val project          = projectGenerator.normal.generate.copy(clientId = client.id)
      val projectUser      = projectUserGenerator.normal.generate.copy(projectId = project.id)

      val organizationFilter     = OrganizationFilters(slug = Some(organization.slug))
      val organizationUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val projectFilters         = ProjectFilters(organizationIds = Some(Seq(organization.id)))
      val clientFilters          = ClientFilters(organizationIds = Some(Seq(organization.id)))
      val projectUserFilters     = ProjectUserFilters(userId = Some(authUser.id), projectIds = Some(Seq(project.id)))

      val data = Seq(OrganizationUserDetailsRow(projectUser.role, project.id, client.id, project.name, client.name))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter)).thenReturn(Some(organizationUser).asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(Seq(project).asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(projectUserFilters)).thenReturn(Seq(projectUser).asDBIO)

      sut.details(authUser, organization.slug, authUser.id).asserting(_ shouldEqual OrganizationUserDetailsResult.Ok(data))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(organizationUserFilter)
      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(projectUserRepository).list(projectUserFilters)
    }
    "return Ok with service data if organization owner are asking about their employees" in new TestCase {
      val organization     = organizationGenerator.normal.generate.copy(createdBy = authUser.id)
      val organizationUser = organizationUserGenerator.normal.generate
      val client           = clientGenerator.normal.generate
      val project          = projectGenerator.normal.generate.copy(clientId = client.id)
      val projectUser      = projectUserGenerator.normal.generate.copy(projectId = project.id)
      val userId           = userIdGenerator.normal.generate

      val organizationFilter     = OrganizationFilters(slug = Some(organization.slug))
      val organizationUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
      val projectFilters         = ProjectFilters(organizationIds = Some(Seq(organization.id)))
      val clientFilters          = ClientFilters(organizationIds = Some(Seq(organization.id)))
      val projectUserFilters     = ProjectUserFilters(userId = Some(userId), projectIds = Some(Seq(project.id)))

      val data = Seq(OrganizationUserDetailsRow(projectUser.role, project.id, client.id, project.name, client.name))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter)).thenReturn(Some(organizationUser).asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(Seq(project).asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(projectUserFilters)).thenReturn(Seq(projectUser).asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.Ok(data))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(organizationUserFilter)
      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(projectUserRepository).list(projectUserFilters)
    }
    "return Ok with service data if an organization manager requests another member" in new TestCase {
      val userId       = userIdGenerator.normal.generate
      val organization = organizationGenerator.normal.generate.copy(createdBy = userId)
      val requesterOrgUser = organizationUserGenerator.normal.generate.copy(
        organizationId = organization.id,
        userId = authUser.id,
        roles = Seq(OrganizationUserRole.Manager),
        archivedAt = None
      )
      val targetOrgUser = organizationUserGenerator.normal.generate.copy(organizationId = organization.id, userId = userId, archivedAt = None)
      val client        = clientGenerator.normal.generate.copy(organizationId = organization.id)
      val project       = projectGenerator.normal.generate.copy(organizationId = organization.id, clientId = client.id)
      val projectUser   = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = userId)

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val targetFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
      val projectFilters     = ProjectFilters(organizationIds = Some(Seq(organization.id)))
      val clientFilters      = ClientFilters(organizationIds = Some(Seq(organization.id)))
      val projectUserFilters = ProjectUserFilters(userId = Some(userId), projectIds = Some(Seq(project.id)))

      val data = Seq(OrganizationUserDetailsRow(projectUser.role, project.id, client.id, project.name, client.name))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(Some(requesterOrgUser).asDBIO)
      when(organizationUserRepository.find(targetFilter)).thenReturn(Some(targetOrgUser).asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(Seq(project).asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(projectUserFilters)).thenReturn(Seq(projectUser).asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.Ok(data))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
      verify(organizationUserRepository).find(targetFilter)
      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(projectUserRepository).list(projectUserFilters)
    }
    "return Ok with service data if an organization owner requests another member" in new TestCase {
      val userId       = userIdGenerator.normal.generate
      val organization = organizationGenerator.normal.generate.copy(createdBy = userId)
      val requesterOrgUser = organizationUserGenerator.normal.generate.copy(
        organizationId = organization.id,
        userId = authUser.id,
        roles = Seq(OrganizationUserRole.Owner),
        archivedAt = None
      )
      val targetOrgUser = organizationUserGenerator.normal.generate.copy(organizationId = organization.id, userId = userId, archivedAt = None)
      val client        = clientGenerator.normal.generate.copy(organizationId = organization.id)
      val project       = projectGenerator.normal.generate.copy(organizationId = organization.id, clientId = client.id)
      val projectUser   = projectUserGenerator.normal.generate.copy(projectId = project.id, userId = userId)

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val targetFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
      val projectFilters     = ProjectFilters(organizationIds = Some(Seq(organization.id)))
      val clientFilters      = ClientFilters(organizationIds = Some(Seq(organization.id)))
      val projectUserFilters = ProjectUserFilters(userId = Some(userId), projectIds = Some(Seq(project.id)))

      val data = Seq(OrganizationUserDetailsRow(projectUser.role, project.id, client.id, project.name, client.name))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(Some(requesterOrgUser).asDBIO)
      when(organizationUserRepository.find(targetFilter)).thenReturn(Some(targetOrgUser).asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(Seq(project).asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(projectUserFilters)).thenReturn(Seq(projectUser).asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.Ok(data))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
      verify(organizationUserRepository).find(targetFilter)
      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(projectUserRepository).list(projectUserFilters)
    }
    "return OrganizationNoAccess when requester does not have a management role" in new TestCase {
      val userId       = userIdGenerator.normal.generate
      val organization = organizationGenerator.normal.generate.copy(createdBy = userId)
      val requesterOrgUser = organizationUserGenerator.normal.generate.copy(
        organizationId = organization.id,
        userId = authUser.id,
        roles = Seq(OrganizationUserRole.Employee),
        archivedAt = None
      )

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(Some(requesterOrgUser).asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.OrganizationNoAccess)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
    }
    "return OrganizationNoAccess when requester has an external role" in new TestCase {
      val userId       = userIdGenerator.normal.generate
      val organization = organizationGenerator.normal.generate.copy(createdBy = userId)
      val requesterOrgUser = organizationUserGenerator.normal.generate.copy(
        organizationId = organization.id,
        userId = authUser.id,
        roles = Seq(OrganizationUserRole.External),
        archivedAt = None
      )

      val organizationFilter = OrganizationFilters(slug = Some(organization.slug))
      val requesterFilter =
        OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(requesterFilter)).thenReturn(Some(requesterOrgUser).asDBIO)

      sut.details(authUser, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.OrganizationNoAccess)

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(requesterFilter)
    }
    "return Ok with service data if super admin is requesting data" in new TestCase {
      val organization     = organizationGenerator.normal.generate
      val organizationUser = organizationUserGenerator.normal.generate
      val client           = clientGenerator.normal.generate
      val project          = projectGenerator.normal.generate.copy(clientId = client.id)
      val projectUser      = projectUserGenerator.normal.generate.copy(projectId = project.id)
      val userId           = userIdGenerator.normal.generate

      val organizationFilter     = OrganizationFilters(slug = Some(organization.slug))
      val organizationUserFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(userId))
      val projectFilters         = ProjectFilters(organizationIds = Some(Seq(organization.id)))
      val clientFilters          = ClientFilters(organizationIds = Some(Seq(organization.id)))
      val projectUserFilters     = ProjectUserFilters(userId = Some(userId), projectIds = Some(Seq(project.id)))

      val data = Seq(OrganizationUserDetailsRow(projectUser.role, project.id, client.id, project.name, client.name))

      when(organizationRepository.find(organizationFilter)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(organizationUserFilter)).thenReturn(Some(organizationUser).asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(Seq(project).asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(Seq(client).asDBIO)
      when(projectUserRepository.list(projectUserFilters)).thenReturn(Seq(projectUser).asDBIO)

      sut.details(authAdmin, organization.slug, userId).asserting(_ shouldEqual OrganizationUserDetailsResult.Ok(data))

      verify(organizationRepository).find(organizationFilter)
      verify(organizationUserRepository).find(organizationUserFilter)
      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(projectUserRepository).list(projectUserFilters)
    }
  }
}
