package pl.iterators.epoca.organizationdashboard.services

import io.circe.syntax._
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserArchivedAt, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organizationdashboard.services.OrganizationUserListService.{OrganizationUserDto, OrganizationUserListResult}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.{ProjectRepository, ProjectUserRepository}
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserIdentityShortDto}
import pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.utils.log.LoggingSupport

import java.time.Instant
import java.util.UUID

class OrganizationUserListServiceSpec extends ServiceSpec with LoggingSupport {

  lazy val organizationGenerator: AllGenerators[Organization]       = allGenerators[Organization]
  lazy val userGenerator: AllGenerators[User]                       = allGenerators[User]
  lazy val identityGenerator: AllGenerators[UserIdentity]           = allGenerators[UserIdentity]
  lazy val rolesGenerator: AllGenerators[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]]
  lazy val projectUserGenerator: AllGenerators[ProjectUser]         = allGenerators[ProjectUser]
  lazy val projectGenerator: AllGenerators[Project]                 = allGenerators[Project]
  trait TestCase extends BaseScope {
    val projectRepository: ProjectRepository                   = vmock[ProjectRepository]
    val projectUserRepository: ProjectUserRepository           = vmock[ProjectUserRepository]
    val organizationRepository: OrganizationRepository         = vmock[OrganizationRepository]
    val organizationUserRepository: OrganizationUserRepository = vmock[OrganizationUserRepository]
    val userRepository: UserRepository                         = vmock[UserRepository]
    val userIdentityRepository: UserIdentityRepository         = vmock[UserIdentityRepository]

    val sut: OrganizationUserListService = wire[OrganizationUserListService]

    val organization: Organization     = organizationGenerator.normal.generate.copy(archivedAt = None)
    val authOrganization: Organization = organizationGenerator.normal.generate.copy(archivedAt = None, createdBy = authUser.id)
    val rl: Seq[OrganizationUserRole]  = rolesGenerator.normal.generate
  }

  "member avatar contract" should {
    "serialize the stored image URL" in {
      val user = userGenerator.normal.generate.copy(avatar = Some(UserAvatar("https://cdn.test/avatar.png")))
      OrganizationUserDto.from(user, Map.empty, Map.empty).asJson.hcursor.get[String]("avatar") must beRight("https://cdn.test/avatar.png")
    }
    "serialize explicit null when no photo exists" in {
      val user = userGenerator.normal.generate.copy(avatar = None)
      OrganizationUserDto.from(user, Map.empty, Map.empty).asJson.hcursor.downField("avatar").focus must beSome(io.circe.Json.Null)
    }
  }

  "user list" should {
    "return OrganizationNotFound when admin tries to list users of non existing organization" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(None.asDBIO)

      sut.list(authAdmin, organization.slug).asserting(_ shouldEqual OrganizationUserListResult.OrganizationNotFound)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
    }

    "return OperationNotPermitted when user tries to list users of organization with no access to" in new TestCase {
      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(any)).thenReturn(None.asDBIO)
      val checkPermissionFilter = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))

      sut.list(authUser, organization.slug).asserting(_ shouldEqual OrganizationUserListResult.OperationNotPermitted)

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(checkPermissionFilter)
    }

    "return Ok with organization users for user from organization" in new TestCase {
      val checkPermissionOrgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)

      val uId1 = UserId(UUID.randomUUID())
      val uId2 = UserId(UUID.randomUUID())
      val uId3 = UserId(UUID.randomUUID())

      val u1 = userGenerator.normal.generate.copy(id = uId1, archivedAt = None)
      val u2 = userGenerator.normal.generate.copy(id = uId2, archivedAt = None)
      val u3 = userGenerator.normal.generate.copy(id = uId3, archivedAt = None)

      val u1i = identityGenerator.normal.generate.copy(userId = uId1, `type` = IdentityType.MailAddress)
      val u3i = identityGenerator.normal.generate.copy(userId = uId3, `type` = IdentityType.Phone)

      val listOrgUsers =
        Seq(
          OrganizationUser(OrganizationUserId.random, organization.id, uId1, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId2, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId3, rl, None)
        )

      val checkPermissionFilter                 = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val projectUserFilter: ProjectUserFilters = ProjectUserFilters(userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      val projectFilter: ProjectFilters         = ProjectFilters(ids = Some(Seq()), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(checkPermissionFilter))
        .thenReturn(Some(checkPermissionOrgUser).asDBIO)
      when(organizationRepository.find(any))
        .thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(checkPermissionFilter))
        .thenReturn(listOrgUsers.asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq().asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq().asDBIO)
      when(userRepository.list(UserFilters(ids = Some(Seq(uId1, uId2, uId3))))).thenReturn(Seq(u1, u2, u3).asDBIO)

      when(userIdentityRepository.list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true)))))
        .thenReturn(Seq(u1i, u3i).asDBIO)

      sut
        .list(authUser, organization.slug)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers should containTheSameElementsAs(
            Seq(
              OrganizationUserDto(uId1, u1.firstName, u1.lastName, rl, Seq(UserIdentityShortDto(u1i)), archived = false, avatar = u1.avatar),
              OrganizationUserDto(uId2, u2.firstName, u2.lastName, rl, Nil, archived = false, avatar = u2.avatar),
              OrganizationUserDto(uId3, u3.firstName, u3.lastName, rl, Seq(UserIdentityShortDto(u3i)), archived = false, avatar = u3.avatar)
            )
          )
        )

      verify(organizationUserRepository).find(checkPermissionFilter)
      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(checkPermissionFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(userRepository).list(UserFilters(ids = Some(Seq(uId1, uId2, uId3))))
      verify(userIdentityRepository).list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true))))
    }
    "return archived members marked as archived only when they are requested" in new TestCase {
      val owner = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Seq(OrganizationUserRole.Owner), None)

      val activeId   = UserId(UUID.randomUUID())
      val archivedId = UserId(UUID.randomUUID())
      val active     = userGenerator.normal.generate.copy(id = activeId, archivedAt = None)
      val archived   = userGenerator.normal.generate.copy(id = archivedId, archivedAt = None)

      val activeMember = OrganizationUser(OrganizationUserId.random, organization.id, activeId, rl, None)
      val archivedMember =
        OrganizationUser(
          OrganizationUserId.random,
          organization.id,
          archivedId,
          rl,
          Some(OrganizationUserArchivedAt(Instant.parse("2026-09-01T00:00:00Z")))
        )

      val checkPermissionFilter                 = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val membersFilter                         = OrganizationUserFilters(organizationId = Some(organization.id))
      val projectUserFilter: ProjectUserFilters = ProjectUserFilters(userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      val projectFilter: ProjectFilters         = ProjectFilters(ids = Some(Seq()), organizationIds = Some(Seq(organization.id)))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.find(checkPermissionFilter)).thenReturn(Some(owner).asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq().asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq().asDBIO)
      when(organizationUserRepository.list(membersFilter)).thenReturn(Seq(activeMember).asDBIO)
      when(organizationUserRepository.list(membersFilter.copy(includeArchived = true))).thenReturn(Seq(activeMember, archivedMember).asDBIO)
      when(userRepository.list(UserFilters(ids = Some(Seq(activeId, archivedId))))).thenReturn(Seq(active, archived).asDBIO)
      when(userIdentityRepository.list(UserIdentityFilters(userIds = Some(Seq(activeId, archivedId)), confirmed = Some(UserIdentityConfirmed(true)))))
        .thenReturn(Seq().asDBIO)

      sut
        .list(authUser, organization.slug, includeArchivedMembers = true)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers should containTheSameElementsAs(
            Seq(
              OrganizationUserDto(activeId, active.firstName, active.lastName, rl, Nil, archived = false, avatar = active.avatar),
              OrganizationUserDto(archivedId, archived.firstName, archived.lastName, rl, Nil, archived = true, avatar = archived.avatar)
            )
          )
        )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).find(checkPermissionFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(organizationUserRepository).list(membersFilter)
      verify(organizationUserRepository).list(membersFilter.copy(includeArchived = true))
      verify(userRepository).list(UserFilters(ids = Some(Seq(activeId, archivedId))))
      verify(userIdentityRepository).list(
        UserIdentityFilters(userIds = Some(Seq(activeId, archivedId)), confirmed = Some(UserIdentityConfirmed(true)))
      )
    }

    "return Ok with organization users for project owner" in new TestCase {
      val checkPermissionOrgUser = OrganizationUser(OrganizationUserId.random, organization.id, authUser.id, Nil, None)
      val project                = projectGenerator.normal.generate
      val projectUser            = projectUserGenerator.normal.generate.copy(role = ProjectRole.Owner, projectId = project.id)

      val uId1 = UserId(UUID.randomUUID())
      val uId2 = UserId(UUID.randomUUID())
      val uId3 = UserId(UUID.randomUUID())

      val u1 = userGenerator.normal.generate.copy(id = uId1, archivedAt = None)
      val u2 = userGenerator.normal.generate.copy(id = uId2, archivedAt = None)
      val u3 = userGenerator.normal.generate.copy(id = uId3, archivedAt = None)

      val u1i = identityGenerator.normal.generate.copy(userId = uId1, `type` = IdentityType.MailAddress)
      val u3i = identityGenerator.normal.generate.copy(userId = uId3, `type` = IdentityType.Phone)

      val listOrgUsers =
        Seq(
          OrganizationUser(OrganizationUserId.random, organization.id, uId1, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId2, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId3, rl, None)
        )

      val listOrgUsersFilter                    = OrganizationUserFilters(organizationId = Some(organization.id))
      val checkPermissionFilter                 = OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(authUser.id))
      val projectUserFilter: ProjectUserFilters = ProjectUserFilters(userId = Some(authUser.id), role = Some(ProjectRole.Owner))
      val projectFilter: ProjectFilters         = ProjectFilters(ids = Some(Seq(project.id)), organizationIds = Some(Seq(organization.id)))

      when(organizationUserRepository.find(checkPermissionFilter))
        .thenReturn(Some(checkPermissionOrgUser).asDBIO)
      when(organizationRepository.find(any))
        .thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(listOrgUsersFilter))
        .thenReturn(listOrgUsers.asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq(projectUser).asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq(project).asDBIO)
      when(userRepository.list(UserFilters(ids = Some(Seq(uId1, uId2, uId3))))).thenReturn(Seq(u1, u2, u3).asDBIO)

      when(userIdentityRepository.list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true)))))
        .thenReturn(Seq(u1i, u3i).asDBIO)

      sut
        .list(authUser, organization.slug, includeArchived = false)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers should containTheSameElementsAs(
            Seq(
              OrganizationUserDto(uId1, u1.firstName, u1.lastName, rl, Seq(UserIdentityShortDto(u1i)), archived = false, avatar = u1.avatar),
              OrganizationUserDto(uId2, u2.firstName, u2.lastName, rl, Nil, archived = false, avatar = u2.avatar),
              OrganizationUserDto(uId3, u3.firstName, u3.lastName, rl, Seq(UserIdentityShortDto(u3i)), archived = false, avatar = u3.avatar)
            )
          )
        )

      verify(organizationUserRepository).find(checkPermissionFilter)
      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(listOrgUsersFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(userRepository).list(UserFilters(ids = Some(Seq(uId1, uId2, uId3))))
      verify(userIdentityRepository).list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true))))
    }

    "return Ok with organization users for admin" in new TestCase {
      val uId1 = UserId(UUID.randomUUID())
      val uId2 = UserId(UUID.randomUUID())
      val uId3 = UserId(UUID.randomUUID())

      val u1 = userGenerator.normal.generate.copy(id = uId1, archivedAt = None)
      val u2 = userGenerator.normal.generate.copy(id = uId2, archivedAt = None)
      val u3 = userGenerator.normal.generate.copy(id = uId3, archivedAt = None)

      val u1i = identityGenerator.normal.generate.copy(userId = uId1, `type` = IdentityType.MailAddress)
      val u3i = identityGenerator.normal.generate.copy(userId = uId3, `type` = IdentityType.Phone)

      val listOrgUsers =
        Seq(
          OrganizationUser(OrganizationUserId.random, organization.id, uId1, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId2, rl, None),
          OrganizationUser(OrganizationUserId.random, organization.id, uId3, rl, None)
        )

      val listOrgUsersFilter                    = OrganizationUserFilters(organizationId = Some(organization.id))
      val projectUserFilter: ProjectUserFilters = ProjectUserFilters(userId = Some(authAdmin.id), role = Some(ProjectRole.Owner))
      val projectFilter: ProjectFilters         = ProjectFilters(ids = Some(Seq()), organizationIds = Some(Seq(organization.id)))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(listOrgUsersFilter)).thenReturn(listOrgUsers.asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq().asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq().asDBIO)
      when(userRepository.list(any)).thenReturn(Seq(u1, u2, u3).asDBIO)

      when(userIdentityRepository.list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true)))))
        .thenReturn(Seq(u1i, u3i).asDBIO)

      sut
        .list(authAdmin, organization.slug)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers should containTheSameElementsAs(
            Seq(
              OrganizationUserDto(uId1, u1.firstName, u1.lastName, rl, Seq(UserIdentityShortDto(u1i)), archived = false, avatar = u1.avatar),
              OrganizationUserDto(uId2, u2.firstName, u2.lastName, rl, Nil, archived = false, avatar = u2.avatar),
              OrganizationUserDto(uId3, u3.firstName, u3.lastName, rl, Seq(UserIdentityShortDto(u3i)), archived = false, avatar = u3.avatar)
            )
          )
        )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(listOrgUsersFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(userRepository).list(UserFilters(ids = Some(Seq(uId1, uId2, uId3))))
      verify(userIdentityRepository).list(UserIdentityFilters(userIds = Some(Seq(uId1, uId2, uId3)), confirmed = Some(UserIdentityConfirmed(true))))
    }

    "exclude service accounts from serialized organization dashboard users" in new TestCase {
      val human          = userGenerator.normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val serviceAccount = userGenerator.normal.generate.copy(accountType = UserAccountType.ServiceAccount, archivedAt = None)
      val memberships = Seq(
        OrganizationUser(OrganizationUserId.random, organization.id, human.id, rl, None),
        OrganizationUser(OrganizationUserId.random, organization.id, serviceAccount.id, rl, None)
      )
      val membershipFilter  = OrganizationUserFilters(organizationId = Some(organization.id))
      val projectUserFilter = ProjectUserFilters(userId = Some(authAdmin.id), role = Some(ProjectRole.Owner))
      val projectFilter     = ProjectFilters(ids = Some(Seq()), organizationIds = Some(Seq(organization.id)))
      val userFilter        = UserFilters(ids = Some(Seq(human.id, serviceAccount.id)))
      val identityFilter    = UserIdentityFilters(userIds = Some(Seq(human.id, serviceAccount.id)), confirmed = Some(UserIdentityConfirmed(true)))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(membershipFilter)).thenReturn(memberships.asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq().asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq().asDBIO)
      when(userIdentityRepository.list(identityFilter)).thenReturn(Seq().asDBIO)
      when(userRepository.list(userFilter)).thenReturn(Seq(human).asDBIO)

      sut
        .list(authAdmin, organization.slug)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers shouldEqual Seq(
            OrganizationUserDto(human.id, human.firstName, human.lastName, rl, Nil, archived = false, avatar = human.avatar)
          )
        )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(membershipFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(userIdentityRepository).list(identityFilter)
      verify(userRepository).list(userFilter)
    }

    "return active and archived users with their status when includeArchived is true" in new TestCase {
      val activeUser   = userGenerator.normal.generate.copy(archivedAt = None)
      val archivedUser = userGenerator.normal.generate.copy(archivedAt = Some(allGenerators[UserArchivedAt].normal.generate))
      val listOrgUsers = Seq(
        OrganizationUser(OrganizationUserId.random, organization.id, activeUser.id, rl, None),
        OrganizationUser(OrganizationUserId.random, organization.id, archivedUser.id, rl, None)
      )
      val listOrgUsersFilter                    = OrganizationUserFilters(organizationId = Some(organization.id))
      val projectUserFilter: ProjectUserFilters = ProjectUserFilters(userId = Some(authAdmin.id), role = Some(ProjectRole.Owner))
      val projectFilter: ProjectFilters         = ProjectFilters(ids = Some(Seq()), organizationIds = Some(Seq(organization.id)))
      val userFilter                            = UserFilters(ids = Some(Seq(activeUser.id, archivedUser.id)), includeArchived = true)
      val identityFilter = UserIdentityFilters(userIds = Some(Seq(activeUser.id, archivedUser.id)), confirmed = Some(UserIdentityConfirmed(true)))

      when(organizationRepository.find(any)).thenReturn(Some(organization).asDBIO)
      when(organizationUserRepository.list(listOrgUsersFilter)).thenReturn(listOrgUsers.asDBIO)
      when(projectRepository.list(projectFilter)).thenReturn(Seq().asDBIO)
      when(projectUserRepository.list(projectUserFilter)).thenReturn(Seq().asDBIO)
      when(userRepository.list(userFilter)).thenReturn(Seq(activeUser, archivedUser).asDBIO)
      when(userIdentityRepository.list(identityFilter)).thenReturn(Seq().asDBIO)

      sut
        .list(authAdmin, organization.slug, includeArchived = true)
        .asserting(
          _.asInstanceOf[OrganizationUserListResult.Ok].orgUsers should containTheSameElementsAs(
            Seq(
              OrganizationUserDto(activeUser.id, activeUser.firstName, activeUser.lastName, rl, Nil, archived = false, avatar = activeUser.avatar),
              OrganizationUserDto(
                archivedUser.id,
                archivedUser.firstName,
                archivedUser.lastName,
                rl,
                Nil,
                archived = true,
                avatar = archivedUser.avatar
              )
            )
          )
        )

      verify(organizationRepository).find(OrganizationFilters(slug = Some(organization.slug)))
      verify(organizationUserRepository).list(listOrgUsersFilter)
      verify(projectUserRepository).list(projectUserFilter)
      verify(projectRepository).list(projectFilter)
      verify(userRepository).list(userFilter)
      verify(userIdentityRepository).list(identityFilter)
    }

  }
}
