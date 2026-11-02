package pl.iterators.epoca.project.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectRole.{Member, Owner}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.Generators

class ProjectUserSpec extends SpecificationLike with Generators {
  val idsSetGenerator: Generator[Set[UserId]]              = allGenerators[Set[UserId]].normal
  val idGenerator: Generator[UserId]                       = allGenerators[UserId].normal
  val projectUserSetGenerator: Generator[Set[ProjectUser]] = allGenerators[Set[ProjectUser]].normal
  val projectUserGenerator: Generator[ProjectUser]         = allGenerators[ProjectUser].normal

  "Project user" should {
    "return assigned users for new project (no duplicates)" in {
      val ownerIds  = idsSetGenerator.generate
      val memberIds = idsSetGenerator.generate

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, Set.empty)

      val expected = (ownerIds.map(ProjectUserRole(_, Owner)) ++ memberIds.map(ProjectUserRole(_, Member))).toSeq

      projectUsersChanges.toAssign should containTheSameElementsAs(expected)
      projectUsersChanges.toDeassign shouldEqual Nil
    }

    "return assigned users for new project with id duplicated in favour of owner" in {
      val duplicatedId = idGenerator.generate
      val ownerIds     = idsSetGenerator.generate + duplicatedId
      val memberIds    = idsSetGenerator.generate + duplicatedId

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, Set.empty)

      val expected = (ownerIds.map(ProjectUserRole(_, Owner)) ++ (memberIds - duplicatedId).map(ProjectUserRole(_, Member))).toSeq

      projectUsersChanges.toAssign should containTheSameElementsAs(expected)
      projectUsersChanges.toDeassign shouldEqual Nil
    }

    "return assigned users for existing project (no duplicates)" in {
      val ownerIds     = idsSetGenerator.generate
      val memberIds    = idsSetGenerator.generate
      val currentUsers = projectUserSetGenerator.generate

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, currentUsers)

      val expectedAssign   = (ownerIds.map(ProjectUserRole(_, Owner)) ++ memberIds.map(ProjectUserRole(_, Member))).toSeq
      val expectedDeassign = currentUsers.map(_.id).toSeq

      projectUsersChanges.toAssign should containTheSameElementsAs(expectedAssign)
      projectUsersChanges.toDeassign should containTheSameElementsAs(expectedDeassign)
    }

    "return assigned users for existing project, same set for owners and members (no duplicated with current user)" in {
      val ownerIds     = idsSetGenerator.generate
      val memberIds    = ownerIds
      val currentUsers = projectUserSetGenerator.generate

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, currentUsers)

      val expectedAssign   = ownerIds.map(ProjectUserRole(_, Owner)).toSeq
      val expectedDeassign = currentUsers.map(_.id).toSeq

      projectUsersChanges.toAssign should containTheSameElementsAs(expectedAssign)
      projectUsersChanges.toDeassign should containTheSameElementsAs(expectedDeassign)
    }

    "return assigned users for existing project, same set for owners and members, also members === currentUsers" in {
      val currentUsers = projectUserSetGenerator.generate.map(_.copy(role = Member))
      val ownerIds     = currentUsers.map(_.userId)
      val memberIds    = currentUsers.map(_.userId)

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, currentUsers)

      val expectedAssign   = currentUsers.map(projectUser => ProjectUserRole(projectUser.userId, Owner)).toSeq
      val expectedDeassign = currentUsers.map(_.id).toSeq

      projectUsersChanges.toAssign should containTheSameElementsAs(expectedAssign)
      projectUsersChanges.toDeassign should containTheSameElementsAs(expectedDeassign)
    }

    "return assigned users for existing project, same set for owners and members, also owners === currentUsers" in {
      val currentUsers = projectUserSetGenerator.generate.map(_.copy(role = Owner))
      val ownerIds     = currentUsers.map(_.userId)
      val memberIds    = currentUsers.map(_.userId)

      val projectUsersChanges = ProjectUser.getProjectUserChanges(ownerIds, memberIds, currentUsers)

      val expectedAssign   = Nil
      val expectedDeassign = Nil

      projectUsersChanges.toAssign should containTheSameElementsAs(expectedAssign)
      projectUsersChanges.toDeassign should containTheSameElementsAs(expectedDeassign)
    }
  }
}
