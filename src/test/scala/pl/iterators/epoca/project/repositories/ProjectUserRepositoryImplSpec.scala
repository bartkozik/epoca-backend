package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectRole, ProjectUserArchivedAt, ProjectUserId}
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.ProjectUserRepository.ProjectUserFilters
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.project.repositories.tables.ProjectUserTable
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._

import java.time.Instant
import java.util.UUID
import scala.util.Failure

class ProjectUserRepositoryImplSpec
    extends PgRepositorySpec
    with OrganizationFixture
    with ClientFixture
    with ProjectFixture
    with UserFixture
    with ServiceAccountFixture {

  private val rep: ProjectUserRepository = new ProjectUserRepositoryImpl()
  private val rawProjectUsers            = TableQuery[ProjectUserTable]

  val organizationGenerator: Generator[Organization] = allGenerators[Organization].maximal
  val projectGenerator: Generator[Project]           = allGenerators[Project].maximal
  val clientGenerator: Generator[Client]             = allGenerators[Client].maximal
  val userGenerator: Generator[User]                 = allGenerators[User].maximal
  val roleGenerator: Generator[ProjectRole]          = allGenerators[ProjectRole].maximal
  val instantGenerator: Generator[Instant]           = allGenerators[Instant].normal

  "CRUD" should {
    "work as follows" in withDatabase {
      val u1   = userGenerator.generate
      val u2   = userGenerator.generate
      val org  = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val clt  = clientGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val pro1 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro2 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro3 = projectGenerator.generate.copy(archivedAt = None, createdBy = u2.id, clientId = clt.id)
      val rl   = roleGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withOrganization(org)
        _   <- withClient(org, clt)
        _   <- withProject(org, clt, pro1)
        _   <- withProject(org, clt, pro2)
        _   <- withProject(org, clt, pro3)
        r11 <- rep.insert(generateProjectUser(pro1, u1, rl))
        r12 <- rep.insert(generateProjectUser(pro1, u2, rl))
        r21 <- rep.insert(generateProjectUser(pro2, u1, rl))
        r32 <- rep.insert(generateProjectUser(pro3, u2, rl))

        f11 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro1.id)), userId = Some(u1.id)))
        f12 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro1.id)), userId = Some(u2.id)))
        f32 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro3.id)), userId = Some(u2.id)))

        projectIds = Some(Seq(pro1.id, pro2.id, pro3.id))
        l0 <- rep.list(ProjectUserFilters(projectIds = projectIds))
        l1 <- rep.list(ProjectUserFilters(projectIds = projectIds, userId = Some(u1.id)))
        l2 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro1.id))))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        _ <- rep.update(r11.copy(archivedAt = Some(ProjectUserArchivedAt(now))))
        _ <- rep.update(r12.copy(archivedAt = Some(ProjectUserArchivedAt(now))))
        _ <- rep.update(r21.copy(archivedAt = Some(ProjectUserArchivedAt(now))))

        ld0 <- rep.list(ProjectUserFilters(projectIds = projectIds))
        ld1 <- rep.list(ProjectUserFilters(projectIds = projectIds, userId = Some(u1.id)))
        ld2 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro1.id))))

      } yield {
        f11 shouldEqual Some(r11)
        f12 shouldEqual Some(r12)
        f32 shouldEqual Some(r32)

        l0 should containTheSameElementsAs(Seq(r11, r21, r32, r12))
        l1 should containTheSameElementsAs(Seq(r11, r21))
        l2 should containTheSameElementsAs(Seq(r11, r12))

        ld0 should containTheSameElementsAs(Seq(r32))
        ld1 should containTheSameElementsAs(Seq.empty)
        ld2 should containTheSameElementsAs(Seq.empty)
      }
    }
  }

  "human user isolation" should {
    "reject a direct service-account assignment without committing a row" in withDatabase {
      val human   = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val org     = organizationGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val client  = clientGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val project = projectGenerator.generate.copy(archivedAt = None, organizationId = org.id, createdBy = human.id, clientId = client.id)

      for {
        _                   <- withUser(human)
        _                   <- withOrganization(org)
        _                   <- withClient(org, client)
        _                   <- withProject(org, client, project)
        legalServiceAccount <- withLegalServiceAccount(org, human)
        serviceAccountRecord = generateProjectUser(project, legalServiceAccount.user, roleGenerator.generate)
        insertResult          <- rep.insert(serviceAccountRecord).asTry
        committedServiceUsers <- rawProjectUsers.filter(_.id === serviceAccountRecord.id).length.result
      } yield {
        insertResult should beAnInstanceOf[Failure[_]]
        committedServiceUsers shouldEqual 0
      }
    }

    "reject a mixed bulk assignment atomically without committing its human row" in withDatabase {
      val human   = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val org     = organizationGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val client  = clientGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val project = projectGenerator.generate.copy(archivedAt = None, organizationId = org.id, createdBy = human.id, clientId = client.id)

      for {
        _                   <- withUser(human)
        _                   <- withOrganization(org)
        _                   <- withClient(org, client)
        _                   <- withProject(org, client, project)
        legalServiceAccount <- withLegalServiceAccount(org, human)
        humanRecord   = generateProjectUser(project, human, roleGenerator.generate)
        serviceRecord = generateProjectUser(project, legalServiceAccount.user, roleGenerator.generate)
        insertResult     <- rep.insert(Seq(humanRecord, serviceRecord)).asTry
        committedRecords <- rawProjectUsers.filter(_.id inSet Seq(humanRecord.id, serviceRecord.id)).length.result
      } yield {
        insertResult should beAnInstanceOf[Failure[_]]
        committedRecords shouldEqual 0
      }
    }

    "exclude service account assignments from all repository reads" in withDatabase {
      val human       = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val org         = organizationGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val client      = clientGenerator.generate.copy(archivedAt = None, createdBy = human.id)
      val project     = projectGenerator.generate.copy(archivedAt = None, createdBy = human.id, clientId = client.id)
      val role        = roleGenerator.generate
      val humanRecord = generateProjectUser(project, human, role)

      for {
        _                   <- withUser(human)
        _                   <- withOrganization(org)
        _                   <- withClient(org, client)
        _                   <- withProject(org, client, project)
        legalServiceAccount <- withLegalServiceAccount(org, human)
        serviceAccountRecord = generateProjectUser(project, legalServiceAccount.user, role)
        _              <- rawProjectUsers += serviceAccountRecord
        _              <- rep.insert(humanRecord)
        direct         <- rep.find(ProjectUserFilters(projectIds = Some(Seq(project.id)), userId = Some(serviceAccountRecord.userId)))
        listed         <- rep.list(ProjectUserFilters(projectIds = Some(Seq(project.id))))
        listedArchived <- rep.list(ProjectUserFilters(projectIds = Some(Seq(project.id)), includeArchived = true))
        locked         <- rep.lockOrdered(ProjectUserFilters(projectIds = Some(Seq(project.id))))
      } yield {
        direct shouldEqual None
        listed should containTheSameElementsAs(Seq(humanRecord))
        listedArchived should containTheSameElementsAs(Seq(humanRecord))
        locked should containTheSameElementsAs(Seq(humanRecord))
      }
    }
  }

  "ordered row locking" should {
    "return project users by project, user and id regardless of insert order" in withDatabase {
      val lowUuid     = UUID.fromString("00000000-0000-0000-0000-000000000001")
      val highUuid    = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
      val lowUser     = userGenerator.generate.copy(id = UserId(lowUuid))
      val highUser    = userGenerator.generate.copy(id = UserId(highUuid))
      val org         = organizationGenerator.generate.copy(archivedAt = None, createdBy = lowUser.id)
      val clt         = clientGenerator.generate.copy(archivedAt = None, createdBy = lowUser.id)
      val lowProject  = projectGenerator.generate.copy(id = ProjectId(lowUuid), archivedAt = None, createdBy = lowUser.id, clientId = clt.id)
      val highProject = projectGenerator.generate.copy(id = ProjectId(highUuid), archivedAt = None, createdBy = lowUser.id, clientId = clt.id)
      val role        = roleGenerator.generate
      val first       = generateProjectUser(lowProject, lowUser, role).copy(id = ProjectUserId(lowUuid))
      val second      = generateProjectUser(lowProject, lowUser, role).copy(id = ProjectUserId(highUuid))
      val third       = generateProjectUser(lowProject, highUser, role)
      val fourth      = generateProjectUser(highProject, lowUser, role)
      val expected    = Seq(first, second, third, fourth).sortBy(value => (value.projectId.toString, value.userId.toString, value.id.toString))

      for {
        _      <- withUser(lowUser)
        _      <- withUser(highUser)
        _      <- withOrganization(org)
        _      <- withClient(org, clt)
        _      <- withProject(org, clt, lowProject)
        _      <- withProject(org, clt, highProject)
        _      <- rep.insert(expected.reverse)
        locked <- rep.lockOrdered(ProjectUserFilters(projectIds = Some(Seq(highProject.id, lowProject.id))))
      } yield locked shouldEqual expected
    }
  }

  "bulk insert and archive" should {
    "work as follows" in withDatabase {
      val u1  = userGenerator.generate
      val u2  = userGenerator.generate
      val u3  = userGenerator.generate
      val u4  = userGenerator.generate
      val org = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val clt = clientGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val pro = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val rl  = roleGenerator.generate
      val pu1 = generateProjectUser(pro, u1, rl)
      val pu2 = generateProjectUser(pro, u2, rl)
      val pu3 = generateProjectUser(pro, u3, rl)
      val pu4 = generateProjectUser(pro, u4, rl)

      for {
        _ <- withUser(u1)
        _ <- withUser(u2)
        _ <- withUser(u3)
        _ <- withUser(u4)
        _ <- withOrganization(org)
        _ <- withClient(org, clt)
        _ <- withProject(org, clt, pro)

        i13 <- rep.insert(List(pu1, pu3))
        i24 <- rep.insert(List(pu2, pu4))

        f1 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro.id)), userId = Some(u1.id)))
        f2 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro.id)), userId = Some(u2.id)))
        f3 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro.id)), userId = Some(u3.id)))
        f4 <- rep.find(ProjectUserFilters(projectIds = Some(Seq(pro.id)), userId = Some(u4.id)))

        l1 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro.id))))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        deleted <- rep.bulkArchiveAt(Seq(pu1.id, pu3.id), ProjectUserArchivedAt(now))

        ld1 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro.id))))
        ld2 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro.id)), userId = Some(u1.id)))
      } yield {
        Seq(f1, f3) should containTheSameElementsAs(i13.map(Some(_)))
        Seq(f2, f4) should containTheSameElementsAs(i24.map(Some(_)))

        l1 should containTheSameElementsAs(i13 ++ i24)

        deleted shouldEqual Seq(pu1, pu3).length
        ld1 should containTheSameElementsAs(i24)
        ld2 should containTheSameElementsAs(Seq.empty)
      }
    }
  }

  "filter by provided params" should {
    "userId" in withDatabase {
      val u1   = userGenerator.generate
      val u2   = userGenerator.generate
      val u3   = userGenerator.generate
      val org  = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val clt  = clientGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val pro1 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro2 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro3 = projectGenerator.generate.copy(archivedAt = None, createdBy = u2.id, clientId = clt.id)
      val rl   = roleGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withUser(u3)
        _   <- withOrganization(org)
        _   <- withClient(org, clt)
        _   <- withProject(org, clt, pro1)
        _   <- withProject(org, clt, pro2)
        _   <- withProject(org, clt, pro3)
        r11 <- rep.insert(generateProjectUser(pro1, u1, rl))
        r21 <- rep.insert(generateProjectUser(pro2, u1, rl))
        r31 <- rep.insert(generateProjectUser(pro3, u1, rl))
        r32 <- rep.insert(generateProjectUser(pro3, u2, rl))

        projectIds = Some(Seq(pro1.id, pro2.id, pro3.id))
        l0 <- rep.list(ProjectUserFilters(projectIds = projectIds))
        l1 <- rep.list(ProjectUserFilters(projectIds = projectIds, userId = Some(u1.id)))
        l2 <- rep.list(ProjectUserFilters(projectIds = projectIds, userId = Some(u2.id)))
        l3 <- rep.list(ProjectUserFilters(projectIds = projectIds, userId = Some(u3.id)))
      } yield {

        l0 should containTheSameElementsAs(Seq(r11, r21, r31, r32))
        l1 should containTheSameElementsAs(Seq(r11, r21, r31))
        l2 should containTheSameElementsAs(Seq(r32))
        l3 should containTheSameElementsAs(Seq.empty)
      }
    }

    "projectId" in withDatabase {
      val u1   = userGenerator.generate
      val u2   = userGenerator.generate
      val u3   = userGenerator.generate
      val org  = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val clt  = clientGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val pro1 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro2 = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro3 = projectGenerator.generate.copy(archivedAt = None, createdBy = u2.id, clientId = clt.id)
      val rl   = roleGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withUser(u3)
        _   <- withOrganization(org)
        _   <- withClient(org, clt)
        _   <- withProject(org, clt, pro1)
        _   <- withProject(org, clt, pro2)
        _   <- withProject(org, clt, pro3)
        r11 <- rep.insert(generateProjectUser(pro1, u1, rl))
        r12 <- rep.insert(generateProjectUser(pro1, u2, rl))
        r13 <- rep.insert(generateProjectUser(pro1, u3, rl))
        r23 <- rep.insert(generateProjectUser(pro2, u3, rl))

        l0 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro1.id, pro2.id, pro3.id))))
        l1 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro1.id))))
        l2 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro2.id))))
        l3 <- rep.list(ProjectUserFilters(projectIds = Some(Seq(pro3.id))))
      } yield {

        l0 should containTheSameElementsAs(Seq(r11, r12, r13, r23))
        l1 should containTheSameElementsAs(Seq(r11, r12, r13))
        l2 should containTheSameElementsAs(Seq(r23))
        l3 should containTheSameElementsAs(Seq.empty)
      }
    }
    "notDeletedAt" in withDatabase {
      val u1          = userGenerator.generate
      val u2          = userGenerator.generate
      val u3          = userGenerator.generate
      val org         = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val clt         = clientGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val archivedAt0 = instantGenerator.generate
      val archivedAt1 = archivedAt0.plusSeconds(30000L)
      val archivedAt2 = archivedAt1.plusSeconds(30000L)
      val archivedAt3 = archivedAt2.plusSeconds(30000L)
      val pro1        = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro2        = projectGenerator.generate.copy(archivedAt = None, createdBy = u1.id, clientId = clt.id)
      val pro3        = projectGenerator.generate.copy(archivedAt = None, createdBy = u2.id, clientId = clt.id)
      val rl          = roleGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withUser(u3)
        _   <- withOrganization(org)
        _   <- withClient(org, clt)
        _   <- withProject(org, clt, pro1)
        _   <- withProject(org, clt, pro2)
        _   <- withProject(org, clt, pro3)
        r11 <- rep.insert(generateProjectUser(pro1, u1, rl))
        r12 <- rep.insert(generateProjectUser(pro1, u2, rl, Some(archivedAt1)))
        r13 <- rep.insert(generateProjectUser(pro1, u3, rl, Some(archivedAt2)))
        r23 <- rep.insert(generateProjectUser(pro2, u3, rl, Some(archivedAt3)))

        projectIds = Some(Seq(pro1.id, pro2.id, pro3.id))
        l0 <- rep.list(ProjectUserFilters(projectIds = projectIds, includeArchived = true, notDeletedAt = Some(archivedAt0)))
        l1 <- rep.list(ProjectUserFilters(projectIds = projectIds, includeArchived = true, notDeletedAt = Some(archivedAt1)))
        l2 <- rep.list(ProjectUserFilters(projectIds = projectIds, includeArchived = true, notDeletedAt = Some(archivedAt2)))
        l3 <- rep.list(ProjectUserFilters(projectIds = projectIds, includeArchived = true, notDeletedAt = Some(archivedAt3)))
      } yield {

        l0 should containTheSameElementsAs(Seq(r11, r12, r13, r23))
        l1 should containTheSameElementsAs(Seq(r11, r13, r23))
        l2 should containTheSameElementsAs(Seq(r11, r23))
        l3 should containTheSameElementsAs(Seq(r11))
      }
    }
  }

  private def generateProjectUser(
    project: Project,
    user: User,
    role: ProjectRole,
    archivedAt: Option[Instant] = None
  ): ProjectUser = {
    val projectUserGenerator: Generator[ProjectUser] = allGenerators[ProjectUser].maximal
    projectUserGenerator.generate.copy(
      projectId = project.id,
      userId = user.id,
      role = role,
      archivedAt = archivedAt.map(ProjectUserArchivedAt.apply)
    )
  }
}
