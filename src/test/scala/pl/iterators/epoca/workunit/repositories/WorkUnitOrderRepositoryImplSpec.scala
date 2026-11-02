package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitDate
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import pl.iterators.epoca.workunit.repositories.WorkUnitOrderRepository.WorkUnitOrderFilters

class WorkUnitOrderRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val rep: WorkUnitOrderRepository = new WorkUnitOrderRepositoryImpl()

  val workUnitOrderGenerator: Generator[WorkUnitOrder] = allGenerators[WorkUnitOrder].normal

  "CRUD" should {
    "work as follows" in withDatabase {
      for {
        org <- withOrganization()
        usr <- withUser()

        r1 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr.id))
        r2 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr.id))
        r3 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr.id))

        f1 <- rep.find(WorkUnitOrderFilters(dateFrom = Some(r1.date), dateTo = Some(r1.date)))

        l0 <- rep.list(WorkUnitOrderFilters())

        ru1 = r1.copy(workUnitIds = workUnitOrderGenerator.generate.workUnitIds)
        ru2 = r2.copy(workUnitIds = workUnitOrderGenerator.generate.workUnitIds)

        _ <- rep.upsert(ru1)
        _ <- rep.upsert(ru2)

        lu0 <- rep.list(WorkUnitOrderFilters())

      } yield {
        f1 shouldEqual Some(r1)

        l0 should containTheSameElementsAs(Seq(r1, r2, r3))

        lu0 should containTheSameElementsAs(Seq(ru1, ru2, r3))
      }
    }
  }

  "filter by provided params" should {
    "organizationId" in withDatabase {
      for {
        org1 <- withOrganization()
        org2 <- withOrganization()
        org3 <- withOrganization()
        usr  <- withUser()

        r1 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org1.id, userId = usr.id))
        r2 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org2.id, userId = usr.id))
        r3 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org3.id, userId = usr.id))

        l1 <- rep.list(WorkUnitOrderFilters(organizationId = Some(r1.organizationId)))
        l2 <- rep.list(WorkUnitOrderFilters(organizationId = Some(r2.organizationId)))
        l3 <- rep.list(WorkUnitOrderFilters(organizationId = Some(r3.organizationId)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }

    }
    "userId" in withDatabase {
      for {
        org  <- withOrganization()
        usr1 <- withUser()
        usr2 <- withUser()
        usr3 <- withUser()

        r1 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr1.id))
        r2 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr2.id))
        r3 <- rep.upsert(workUnitOrderGenerator.generate.copy(organizationId = org.id, userId = usr3.id))

        l1 <- rep.list(WorkUnitOrderFilters(userId = Some(r1.userId)))
        l2 <- rep.list(WorkUnitOrderFilters(userId = Some(r2.userId)))
        l3 <- rep.list(WorkUnitOrderFilters(userId = Some(r3.userId)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "dateFrom" in withDatabase {
      val obj1 = workUnitOrderGenerator.generate
      val obj2 = workUnitOrderGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(1)))
      val obj3 = workUnitOrderGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(2)))
      for {
        org <- withOrganization()
        usr <- withUser()

        r1 <- rep.upsert(obj1.copy(organizationId = org.id, userId = usr.id))
        r2 <- rep.upsert(obj2.copy(organizationId = org.id, userId = usr.id))
        r3 <- rep.upsert(obj3.copy(organizationId = org.id, userId = usr.id))

        l1 <- rep.list(WorkUnitOrderFilters(dateFrom = Some(r1.date)))
        l2 <- rep.list(WorkUnitOrderFilters(dateFrom = Some(r2.date)))
        l3 <- rep.list(WorkUnitOrderFilters(dateFrom = Some(r3.date)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1, r2, r3))
        l2 should containTheSameElementsAs(Seq(r3, r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }
    "dateTo" in withDatabase {
      val obj1 = workUnitOrderGenerator.generate
      val obj2 = workUnitOrderGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(1)))
      val obj3 = workUnitOrderGenerator.generate.copy(date = WorkUnitDate(obj1.date.plusDays(2)))
      for {
        org <- withOrganization()
        usr <- withUser()

        r1 <- rep.upsert(obj1.copy(organizationId = org.id, userId = usr.id))
        r2 <- rep.upsert(obj2.copy(organizationId = org.id, userId = usr.id))
        r3 <- rep.upsert(obj3.copy(organizationId = org.id, userId = usr.id))

        l1 <- rep.list(WorkUnitOrderFilters(dateTo = Some(r1.date)))
        l2 <- rep.list(WorkUnitOrderFilters(dateTo = Some(r2.date)))
        l3 <- rep.list(WorkUnitOrderFilters(dateTo = Some(r3.date)))
      } yield {
        l3 should containTheSameElementsAs(Seq(r1, r2, r3))
        l2 should containTheSameElementsAs(Seq(r1, r2))
        l1 should containTheSameElementsAs(Seq(r1))
      }
    }
  }
}
