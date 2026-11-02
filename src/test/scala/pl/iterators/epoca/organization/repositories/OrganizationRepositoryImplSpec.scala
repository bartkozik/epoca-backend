package pl.iterators.epoca.organization.repositories

import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationUserFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber

import java.time.Instant

class OrganizationRepositoryImplSpec extends PgRepositorySpec with OrganizationUserFixture with UserFixture {

  private val rep: OrganizationRepository = new OrganizationRepositoryImpl()

  val organizationGenerator: Generator[Organization]       = allGenerators[Organization].maximal
  val userGenerator: Generator[User]                       = allGenerators[User].maximal
  val rolesGenerator: Generator[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]].maximal

  "CRUD" should {
    "work as follows" in withDatabase {
      for {
        r1 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r3 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id))

        f1 <- rep.find(OrganizationFilters(id = Some(r1.id)))
        f2 <- rep.find(OrganizationFilters(id = Some(r2.id)))
        f3 <- rep.find(OrganizationFilters(id = Some(r3.id)))

        l0 <- rep.list(OrganizationFilters(ids = ids))
        l1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        l2 <- rep.list(OrganizationFilters(id = Some(r2.id)))
        l3 <- rep.list(OrganizationFilters(id = Some(r3.id)))

        ru1 = r1.copy(name = organizationGenerator.generate.name)
        ru2 = r2.copy(name = organizationGenerator.generate.name)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        lu0 <- rep.list(OrganizationFilters(ids = ids))
        lu1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        lu2 <- rep.list(OrganizationFilters(id = Some(r2.id)))
        lu3 <- rep.list(OrganizationFilters(id = Some(r3.id)))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2 = r2.copy(archivedAt = Some(OrganizationArchivedAt(now)))
        rd3 = r3.copy(archivedAt = Some(OrganizationArchivedAt(now)))

        _ <- rep.update(rd2)
        _ <- rep.update(rd3)

        ld0 <- rep.list(OrganizationFilters(ids = ids))
        ld1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        ld2 <- rep.list(OrganizationFilters(id = Some(r2.id)))
        ld3 <- rep.list(OrganizationFilters(id = Some(r3.id)))

      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)

        l0 should containTheSameElementsAs(Seq(r1, r2, r3))
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))

        lu0 should containTheSameElementsAs(Seq(ru1, ru2, r3))
        lu1 should containTheSameElementsAs(Seq(ru1))
        lu2 should containTheSameElementsAs(Seq(ru2))
        lu3 should containTheSameElementsAs(Seq(r3))

        ld0 should containTheSameElementsAs(Seq(ru1))
        ld1 should containTheSameElementsAs(Seq(ru1))
        ld2 should containTheSameElementsAs(Nil)
        ld3 should containTheSameElementsAs(Nil)
      }
    }
  }

  "filter by provided params" should {
    "id" in withDatabase {
      for {
        r1 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r3 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))

        l1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        l2 <- rep.list(OrganizationFilters(id = Some(r2.id)))
        l3 <- rep.list(OrganizationFilters(id = Some(r3.id)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "creatorId" in withDatabase {
      val uid1 = organizationGenerator.generate.createdBy
      val uid2 = organizationGenerator.generate.createdBy
      val uid3 = organizationGenerator.generate.createdBy
      for {
        r1 <- rep.insert(organizationGenerator.generate.copy(createdBy = uid1, archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(createdBy = uid2, archivedAt = None))
        r3 <- rep.insert(organizationGenerator.generate.copy(createdBy = uid2, archivedAt = None))
        r4 <- rep.insert(organizationGenerator.generate.copy(createdBy = uid2, archivedAt = None))
        r5 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id, r4.id, r5.id))

        l1 <- rep.list(OrganizationFilters(ids = ids, creatorId = Some(uid1)))
        l2 <- rep.list(OrganizationFilters(ids = ids, creatorId = Some(uid2)))
        l3 <- rep.list(OrganizationFilters(ids = ids, creatorId = Some(uid3)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2, r3, r4))
        l3 should containTheSameElementsAs(Seq.empty)
      }
    }

    "userId" in withDatabase {
      val u1 = userGenerator.generate.copy(archivedAt = None)
      val u2 = userGenerator.generate.copy(archivedAt = None)
      val rl = rolesGenerator.generate
      for {

        r1 <- rep.insert(organizationGenerator.generate.copy(createdBy = u1.id, archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(createdBy = u2.id, archivedAt = None))
        r3 <- rep.insert(organizationGenerator.generate.copy(createdBy = u2.id, archivedAt = None))
        r4 <- rep.insert(organizationGenerator.generate.copy(createdBy = u2.id, archivedAt = None))
        r5 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))

        _ <- withUser(u1)
        _ <- withUser(u2)
        _ <- withUser(u1.copy(id = r5.createdBy))

        _ <- withCreatorOrganizationUser(r1, rl)
        _ <- withCreatorOrganizationUser(r2, rl)
        _ <- withCreatorOrganizationUser(r3, rl)
        _ <- withCreatorOrganizationUser(r4, rl)
        _ <- withCreatorOrganizationUser(r5, rl)

        ids = Some(Seq(r1.id, r2.id, r3.id, r4.id, r5.id))
        l1 <- rep.list(OrganizationFilters(ids = ids, userId = Some(u1.id)))
        l2 <- rep.list(OrganizationFilters(ids = ids, userId = Some(u2.id)))
        l3 <- rep.list(OrganizationFilters(ids = ids))

      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2, r3, r4))
        l3 should containTheSameElementsAs(Seq(r1, r2, r3, r4, r5))
      }
    }

    "page size and number" in withDatabase {
      for {
        r1 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r3 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r4 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r5 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id, r4.id, r5.id))

        l1 <- rep.list(OrganizationFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        l2 <- rep.list(OrganizationFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))
        l3 <- rep.list(OrganizationFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))))
      } yield {
        l1.size shouldEqual 4
        l2.size shouldEqual 2
        l3.size shouldEqual 2

        l1 shouldEqual (l2 ++ l3)
      }
    }

    "includeArchived" in withDatabase {
      for {
        r1 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        r2 <- rep.insert(organizationGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id))

        l0 <- rep.list(OrganizationFilters(ids = ids))
        l1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        l2 <- rep.list(OrganizationFilters(id = Some(r2.id)))

        li0 <- rep.list(OrganizationFilters(ids = ids, includeArchived = true))
        li1 <- rep.list(OrganizationFilters(id = Some(r1.id), includeArchived = true))
        li2 <- rep.list(OrganizationFilters(id = Some(r2.id), includeArchived = true))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd1 = r1.copy(archivedAt = Some(OrganizationArchivedAt(now)))
        _ <- rep.update(rd1)

        ld0 <- rep.list(OrganizationFilters(ids = ids))
        ld1 <- rep.list(OrganizationFilters(id = Some(r1.id)))
        ld2 <- rep.list(OrganizationFilters(id = Some(r2.id)))

        ldi0 <- rep.list(OrganizationFilters(ids = ids, includeArchived = true))
        ldi1 <- rep.list(OrganizationFilters(id = Some(r1.id), includeArchived = true))
        ldi2 <- rep.list(OrganizationFilters(id = Some(r2.id), includeArchived = true))
      } yield {
        l0 should containTheSameElementsAs(Seq(r1, r2))
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))

        li0 should containTheSameElementsAs(l0)
        li1 should containTheSameElementsAs(l1)
        li2 should containTheSameElementsAs(l2)

        ld0 should containTheSameElementsAs(Seq(r2))
        ld1 should containTheSameElementsAs(Nil)
        ld2 should containTheSameElementsAs(Seq(r2))

        ldi0.map(_.id) should containTheSameElementsAs(l0.map(_.id))
        val rd1 = ldi0.find(_.id == r1.id).get
        rd1.archivedAt should beSome
        rd1.copy(archivedAt = None) shouldEqual r1
        ldi0.find(_.id == r2.id).get shouldEqual r2

        ldi1 should containTheSameElementsAs(Seq(rd1))
        ldi2 should containTheSameElementsAs(Seq(r2))
      }
    }
  }
}
