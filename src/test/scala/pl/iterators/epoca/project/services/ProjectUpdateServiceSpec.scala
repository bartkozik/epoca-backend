package pl.iterators.epoca.project.services

import org.mockito.invocation.InvocationOnMock
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectDescription, ProjectId, ProjectModifiedAt, ProjectName, ProjectRole}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.project.services.ProjectUpdateService.ProjectUpdateResult._
import pl.iterators.epoca.project.services.ProjectUpdateService.{ProjectUpdateRequest, ProjectUpdateResult}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.utils.ServiceSpec
import slick.dbio.DBIO

import java.time.Instant
import java.util.UUID

class ProjectUpdateServiceSpec extends ServiceSpec {
  private val pastModifiedAt = ProjectModifiedAt(Instant.EPOCH)

  lazy val organizationGenerator: AllGenerators[Organization] = allGenerators[Organization]
  lazy val projectGenerator: AllGenerators[Project]           = allGenerators[Project]
  lazy val userGenerator: AllGenerators[User]                 = allGenerators[User]
  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]

    val sut: ProjectUpdateService = wire[ProjectUpdateService]

    val creator: ProjectUser       = allGenerators[ProjectUser].normal.generate
    val owner: ProjectUser         = allGenerators[ProjectUser].normal.generate.copy(role = ProjectRole.Owner)
    val member: ProjectUser        = allGenerators[ProjectUser].normal.generate.copy(role = ProjectRole.Member)
    val organization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None)
    val project: Project = projectGenerator.normal.generate.copy(
      archivedAt = None,
      organizationId = organization.id,
      createdBy = creator.userId,
      modifiedAt = pastModifiedAt
    )
    val request: ProjectUpdateRequest    = allGenerators[ProjectUpdateRequest].maximal.generate
    val roles: Seq[OrganizationUserRole] = Seq(OrganizationUserRole.Manager)

    def requestedUserIds(value: ProjectUpdateRequest): Seq[UserId] =
      (value.ownerIds ++ value.memberIds).toSeq

    def organizationUsersFor(value: ProjectUpdateRequest): Seq[OrganizationUser] =
      requestedUserIds(value).map(userId => OrganizationUser(OrganizationUserId.random, organization.id, userId, Nil, None))

  }

  "update" should {
    "return OrganizationNotFound when admin tries to update project in non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authAdmin, organization.slug, project.id, request).asserting(_ shouldEqual OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return NoAccessToOrganization when user not from organization tries to update project" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, organization.slug, project.id, request).asserting(_ shouldEqual NoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
    }

    "return NoAccessToOrganization when user has insufficient role in project and is not management" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, organization.slug, project.id, request).asserting(_ shouldEqual NotProjectOwner)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectUserRepository).find(
        ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      )
    }

    "return ProjectNotFound when user with access to organization tries to update not existing project" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authUser, organization.slug, project.id, request).asserting(_ shouldEqual ProjectNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
    }

    "return ProjectNotFound when admin tries to update not existing project" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(any)).thenReturn(None.asDBIO)

      sut.update(authAdmin, organization.slug, project.id, request).asserting(_ shouldEqual ProjectNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true))
    }

    "update description when project name and users are unchanged" in new TestCase {
      val currentOwner       = creator.copy(projectId = project.id, role = ProjectRole.Owner)
      val currentProject     = project.copy(description = None, createdBy = currentOwner.userId)
      val updatedDescription = ProjectDescription("Updated in CT-501 regression")
      val updateRequest = request.copy(
        name = Some(currentProject.name),
        description = Some(updatedDescription),
        ownerIds = Set(currentOwner.userId),
        memberIds = Set.empty
      )
      val requestedIds = requestedUserIds(updateRequest)

      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(currentProject.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(currentProject.name))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(currentOwner).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(updateRequest).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      val result: IO[ProjectUpdateResult] = sut.update(authAdmin, organization.slug, currentProject.id, updateRequest)

      result.asserting(_ should beAnInstanceOf[Updated])
      result.asserting(_.asInstanceOf[Updated].project.description shouldEqual Some(updatedDescription))

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository, times(0)).find(nameFilter)
      verify(projectRepository).update(argThat { (updated: Project) =>
        updated.description.contains(updatedDescription)
      })
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(currentProject.id)), forUpdate = true))
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedIds)))
      verify(projectUserRepository).bulkArchiveAt(eqTo(Seq.empty), any)
      verify(projectUserRepository).insert(eqTo(Seq.empty[ProjectUser]))
    }

    "return ProjectNameNotUnique when another active project uses requested name" in new TestCase {
      val currentProject     = project.copy(id = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000001")))
      val requestedName      = ProjectName(s"${currentProject.name}-renamed")
      val conflictingProject = currentProject.copy(id = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000002")), name = requestedName)
      val updateRequest      = request.copy(name = Some(requestedName))

      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(currentProject.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(requestedName))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(Some(conflictingProject).asDBIO)

      sut.update(authAdmin, organization.slug, currentProject.id, updateRequest).asserting(_ shouldEqual ProjectNameNotUnique)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
    }

    "assign a new member when project name is unchanged" in new TestCase {
      val currentOwner      = creator.copy(projectId = project.id, role = ProjectRole.Owner)
      val currentProject    = project.copy(createdBy = currentOwner.userId)
      val updateRequest     = request.copy(name = Some(currentProject.name), ownerIds = Set(currentOwner.userId), memberIds = Set(member.userId))
      val requestedIds      = requestedUserIds(updateRequest)
      val organizationUsers = organizationUsersFor(updateRequest)

      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(currentProject.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = Some(currentProject.name))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(currentOwner).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsers.asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      sut.update(authAdmin, organization.slug, currentProject.id, updateRequest).asserting(_ should beAnInstanceOf[Updated])

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(currentProject.id)), forUpdate = true))
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedIds)))
      verify(projectUserRepository).bulkArchiveAt(any, any)
      verify(projectUserRepository).insert(argThat { (inserted: Seq[ProjectUser]) =>
        inserted.size == 1 &&
        inserted.head.projectId == currentProject.id &&
        inserted.head.userId == member.userId &&
        inserted.head.role == ProjectRole.Member
      })
    }

    "return ProjectUsersHaveNoAccessToOrganization when one of assigned users has no access to organization" in new TestCase {
      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(member).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Nil.asDBIO)

      sut.update(authAdmin, organization.slug, project.id, request).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
      verify(projectRepository, times(0)).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true))
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedUserIds(request))))
    }

    "reject assigning a service account before updating the project" in new TestCase {
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val currentOwner   = creator.copy(projectId = project.id, role = ProjectRole.Owner)
      val currentProject = project.copy(createdBy = currentOwner.userId)
      val updateRequest  = request.copy(name = Some(currentProject.name), ownerIds = Set(currentOwner.userId), memberIds = Set(serviceAccount.id))
      val humanOrganizationOwner = OrganizationUser(OrganizationUserId.random, organization.id, currentOwner.userId, Nil, None)
      val projectFilter          = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(currentProject.id), forUpdate = true)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(currentProject).asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(currentOwner).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(Seq(humanOrganizationOwner).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      sut.update(authAdmin, organization.slug, currentProject.id, updateRequest).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(currentProject.id)), forUpdate = true))
      verify(organizationUserRepository).list(argThat { filters: OrganizationUserFilters =>
        filters.organizationId.contains(organization.id) &&
        filters.userIds.exists(_.toSet == Set(currentOwner.userId, serviceAccount.id))
      })
      verify(projectRepository, times(0)).update(any)
      verify(projectUserRepository, times(0)).insert(any: Seq[ProjectUser])
      verify(projectUserRepository, times(0)).bulkArchiveAt(any, any)
    }

    "map a repository ineligible-user race to the existing project-user access result" in new TestCase {
      val currentOwner  = creator.copy(projectId = project.id, role = ProjectRole.Owner)
      val current       = project.copy(createdBy = currentOwner.userId)
      val human         = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val updateRequest = request.copy(name = Some(current.name), ownerIds = Set(currentOwner.userId), memberIds = Set(human.id))
      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(current.id), forUpdate = true)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(current).asDBIO)
      when(projectUserRepository.list(any)).thenReturn(Seq(currentOwner).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(updateRequest).asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(DBIO.failed(ProjectUserNotPermitted))

      sut.update(authAdmin, organization.slug, current.id, updateRequest).asserting(_ shouldEqual ProjectUsersHaveNoAccessToOrganization)

      verify(organizationRepository).find(any)
      verify(projectRepository).find(any)
      verify(projectUserRepository).list(any)
      verify(organizationUserRepository).list(any)
      verify(projectRepository).update(any)
      verify(projectUserRepository).bulkArchiveAt(any, any)
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }

    "return CreatorNotDeassignable when one of deassigned users is project creator" in new TestCase {
      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(creator).asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(request).asDBIO)

      sut.update(authAdmin, organization.slug, project.id, request).asserting(_ shouldEqual CreatorNotDeassignable)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
      verify(projectRepository, times(0)).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true))
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedUserIds(request))))
    }

    "return Updated for user with access to organization with existing project" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, roles, None)

      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(member).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(request).asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      val result: IO[ProjectUpdateResult] = sut.update(authUser, organization.slug, project.id, request)
      result.asserting(_ should beAnInstanceOf[Updated])

      result.asserting(_.asInstanceOf[Updated].project.name shouldEqual request.name.getOrElse(project.name))
      result.asserting(_.asInstanceOf[Updated].project.description shouldEqual request.description.orElse(project.description))
      result.asserting(_.asInstanceOf[Updated].project.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[Updated].project.modifiedBy shouldEqual authUser.id)
      result.asserting(_.asInstanceOf[Updated].project.archivedAt should beNone)
      result.asserting(
        _.asInstanceOf[Updated].project
          .copy(
            name = project.name,
            description = project.description,
            modifiedAt = project.modifiedAt,
            modifiedBy = project.modifiedBy
          ) shouldEqual project
      )

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
      verify(projectRepository).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true))
      verify(projectUserRepository).bulkArchiveAt(any, any)
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedUserIds(request))))
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }

    "return Updated for user with EMPLOYEE access to organization with Owner role in project" in new TestCase {
      val orgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Employee), None)

      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(Some(orgUser).asDBIO)
      when(projectUserRepository.find(any)).thenReturn(Some(owner).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(member).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(request).asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      val result: IO[ProjectUpdateResult] = sut.update(authUser, organization.slug, project.id, request)
      result.asserting(_ should beAnInstanceOf[Updated])

      result.asserting(_.asInstanceOf[Updated].project.name shouldEqual request.name.getOrElse(project.name))
      result.asserting(_.asInstanceOf[Updated].project.description shouldEqual request.description.orElse(project.description))
      result.asserting(_.asInstanceOf[Updated].project.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[Updated].project.modifiedBy shouldEqual authUser.id)
      result.asserting(_.asInstanceOf[Updated].project.archivedAt should beNone)
      result.asserting(
        _.asInstanceOf[Updated].project
          .copy(
            name = project.name,
            description = project.description,
            modifiedAt = project.modifiedAt,
            modifiedBy = project.modifiedBy
          ) shouldEqual project
      )

      verify(organizationUserRepository).find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id)))
      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectUserRepository).find(
        ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      )
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
      verify(projectRepository).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true))
      verify(projectUserRepository).bulkArchiveAt(any, any)
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedUserIds(request))))
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }

    "return Updated for admin with existing project" in new TestCase {
      val projectFilter = ProjectFilters(organizationIds = Some(Seq(organization.id)), id = Some(project.id), forUpdate = true)
      val nameFilter    = ProjectFilters(organizationIds = Some(Seq(organization.id)), name = request.name)

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(projectRepository.find(projectFilter)).thenReturn(Some(project).asDBIO)
      when(projectRepository.find(nameFilter)).thenReturn(None.asDBIO)
      when(projectRepository.update(any)).thenAnswer { (invocation: InvocationOnMock) =>
        Some(invocation.getArguments()(0).asInstanceOf[Project]).asDBIO
      }
      when(projectUserRepository.list(any)).thenReturn(Seq(member).asDBIO)
      when(projectUserRepository.bulkArchiveAt(any, any)).thenReturn(0.asDBIO)
      when(organizationUserRepository.list(any)).thenReturn(organizationUsersFor(request).asDBIO)
      when(projectUserRepository.insert(any: Seq[ProjectUser])).thenReturn(Nil.asDBIO)

      val result: IO[ProjectUpdateResult] = sut.update(authAdmin, organization.slug, project.id, request)
      result.asserting(_ should beAnInstanceOf[Updated])

      result.asserting(_.asInstanceOf[Updated].project.name shouldEqual request.name.getOrElse(project.name))
      result.asserting(_.asInstanceOf[Updated].project.description shouldEqual request.description.orElse(project.description))
      result.asserting(_.asInstanceOf[Updated].project.modifiedAt.isAfter(pastModifiedAt) should beTrue)
      result.asserting(_.asInstanceOf[Updated].project.modifiedBy shouldEqual authAdmin.id)
      result.asserting(_.asInstanceOf[Updated].project.archivedAt should beNone)
      result.asserting(
        _.asInstanceOf[Updated].project
          .copy(
            name = project.name,
            description = project.description,
            modifiedAt = project.modifiedAt,
            modifiedBy = project.modifiedBy
          ) shouldEqual project
      )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(projectRepository).find(projectFilter)
      verify(projectRepository).find(nameFilter)
      verify(projectRepository).update(any)
      verify(projectUserRepository).list(ProjectUserFilters(projectIds = Some(Seq(project.id)), forUpdate = true))
      verify(projectUserRepository).bulkArchiveAt(any, any)
      verify(organizationUserRepository)
        .list(OrganizationUserFilters(organizationId = Some(organization.id), userIds = Some(requestedUserIds(request))))
      verify(projectUserRepository).insert(any: Seq[ProjectUser])
    }
  }
}
