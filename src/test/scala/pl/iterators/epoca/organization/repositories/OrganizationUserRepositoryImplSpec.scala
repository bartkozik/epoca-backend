package pl.iterators.epoca.organization.repositories

import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserArchivedAt, OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import slick.dbio.{DBIO, Effect, NoStream}
import slick.sql.SqlAction

import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS

class OrganizationUserRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with UserFixture with ServiceAccountFixture {

  private val rep = new OrganizationUserRepositoryImpl()

  private val pastArchivedAt = Instant.parse("2000-01-01T00:00:00Z")

  val organizationGenerator: Generator[Organization]       = allGenerators[Organization].maximal
  val userGenerator: Generator[User]                       = allGenerators[User].maximal
  val rolesGenerator: Generator[Seq[OrganizationUserRole]] = allGenerators[Seq[OrganizationUserRole]].maximal

  "human member reads" should {
    "exclude legal service-account memberships even for direct and archived-inclusive filters" in withDatabase {
      val human        = userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = human.id)

      for {
        _              <- withUser(human)
        _              <- withOrganization(organization)
        humanMember    <- rep.upsert(OrganizationUser(OrganizationUserId.random, organization.id, human.id, rolesGenerator.generate, None))
        serviceAccount <- withLegalServiceAccount(organization, human)
        direct         <- rep.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.user.id)))
        archivedInclusive <-
          rep.find(OrganizationUserFilters(organizationId = Some(organization.id), userId = Some(serviceAccount.user.id), includeArchived = true))
        listed <- rep.list(
                    OrganizationUserFilters(
                      organizationId = Some(organization.id),
                      userIds = Some(Seq(human.id, serviceAccount.user.id)),
                      includeArchived = true
                    )
                  )
      } yield {
        direct should beNone
        archivedInclusive should beNone
        listed shouldEqual Seq(humanMember)
      }
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      val u1                 = userGenerator.generate
      val u2                 = userGenerator.generate
      val org1: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org2: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org3: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      val rl                 = rolesGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withOrganization(org1)
        _   <- withOrganization(org2)
        _   <- withOrganization(org3)
        r11 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u1.id, rl, None))
        r21 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org2.id, u1.id, rl, None))
        r32 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org3.id, u2.id, rl, None))
        r12 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u2.id, rl, None))

        f11 <- rep.find(OrganizationUserFilters(organizationId = Some(org1.id), userId = Some(u1.id)))
        f12 <- rep.find(OrganizationUserFilters(organizationId = Some(org1.id), userId = Some(u2.id)))
        f32 <- rep.find(OrganizationUserFilters(organizationId = Some(org3.id), userId = Some(u2.id)))

        organizationIds = Some(Seq(org1.id, org2.id, org3.id))
        l0 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds))
        l1 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, userId = Some(u1.id)))
        l2 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, organizationId = Some(org1.id)))

        _ <- rep.delete(r11.id, pastArchivedAt)
        _ <- rep.delete(r12.id, pastArchivedAt)
        _ <- rep.delete(r21.id, pastArchivedAt)

        ld0 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds))
        ld1 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, userId = Some(u1.id)))
        ld2 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, organizationId = Some(org1.id)))

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

  "soft-delete timestamp precision" should {
    "store sub-millisecond input at the millisecond boundary and delete it at the equal cutoff" in withDatabase {
      val user          = userGenerator.generate
      val organization  = organizationGenerator.generate.copy(archivedAt = None, createdBy = user.id)
      val roles         = rolesGenerator.generate
      val original      = OrganizationUser(OrganizationUserId.random, organization.id, user.id, roles, None)
      val highPrecision = Instant.parse("2026-07-28T07:28:48.429146789Z")
      val cutoff        = highPrecision.truncatedTo(MILLIS)

      for {
        _                <- withUser(user)
        _                <- withOrganization(organization)
        stored           <- rep.upsert(original)
        deleted          <- rep.delete(stored.id, highPrecision)
        activeAtCutoff   <- rep.list(Seq(stored.id), includeDeleted = false, when = cutoff)
        archivedAtCutoff <- rep.find(stored.id, includeDeleted = true, when = cutoff)
      } yield {
        val expected = stored.copy(archivedAt = Some(OrganizationUserArchivedAt(cutoff)))

        deleted shouldEqual 1
        activeAtCutoff shouldEqual Seq.empty
        archivedAtCutoff shouldEqual Some(expected)
      }
    }

    "leave already millisecond-aligned input unchanged" in withDatabase {
      val user         = userGenerator.generate
      val organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = user.id)
      val roles        = rolesGenerator.generate
      val original     = OrganizationUser(OrganizationUserId.random, organization.id, user.id, roles, None)
      val aligned      = Instant.parse("2026-07-28T07:28:48.429Z")

      for {
        _        <- withUser(user)
        _        <- withOrganization(organization)
        stored   <- rep.upsert(original)
        deleted  <- rep.delete(stored.id, aligned)
        archived <- rep.find(stored.id, includeDeleted = true, when = aligned)
      } yield {
        deleted shouldEqual 1
        archived shouldEqual Some(stored.copy(archivedAt = Some(OrganizationUserArchivedAt(aligned))))
      }
    }

    "keep a future timestamp active until its normalized full-millisecond boundary" in withDatabase {
      val user                   = userGenerator.generate
      val organization           = organizationGenerator.generate.copy(archivedAt = None, createdBy = user.id)
      val roles                  = rolesGenerator.generate
      val original               = OrganizationUser(OrganizationUserId.random, organization.id, user.id, roles, None)
      val cutoff                 = Instant.parse("2026-07-28T07:28:48.429Z")
      val scheduledHighPrecision = cutoff.plusMillis(2).plusNanos(146789)
      val scheduled              = scheduledHighPrecision.truncatedTo(MILLIS)

      for {
        _               <- withUser(user)
        _               <- withOrganization(organization)
        stored          <- rep.upsert(original)
        deleted         <- rep.delete(stored.id, scheduledHighPrecision)
        activeAtCutoff  <- rep.list(Seq(stored.id), includeDeleted = false, when = cutoff)
        activeAtArchive <- rep.list(Seq(stored.id), includeDeleted = false, when = scheduled)
        archived        <- rep.find(stored.id, includeDeleted = true, when = scheduled)
      } yield {
        val expected = stored.copy(archivedAt = Some(OrganizationUserArchivedAt(scheduled)))

        deleted shouldEqual 1
        activeAtCutoff shouldEqual Seq(expected)
        activeAtArchive shouldEqual Seq.empty
        archived shouldEqual Some(expected)
      }
    }
  }

  "filter by provided params" should {
    "userId" in withDatabase {
      val u1                 = userGenerator.generate
      val u2                 = userGenerator.generate
      val u3                 = userGenerator.generate
      val org1: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org2: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org3: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      val rl                 = rolesGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withUser(u3)
        _   <- withOrganization(org1)
        _   <- withOrganization(org2)
        _   <- withOrganization(org3)
        r11 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u1.id, rl, None))
        r21 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org2.id, u1.id, rl, None))
        r31 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org3.id, u1.id, rl, None))
        r32 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org3.id, u2.id, rl, None))

        organizationIds = Some(Seq(org1.id, org2.id, org3.id))
        l0 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds))
        l1 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, userId = Some(u1.id)))
        l2 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, userId = Some(u2.id)))
        l3 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, userId = Some(u3.id)))
      } yield {

        l0 should containTheSameElementsAs(Seq(r11, r21, r31, r32))
        l1 should containTheSameElementsAs(Seq(r11, r21, r31))
        l2 should containTheSameElementsAs(Seq(r32))
        l3 should containTheSameElementsAs(Seq.empty)
      }
    }

    "organizationId" in withDatabase {
      val u1                 = userGenerator.generate
      val u2                 = userGenerator.generate
      val u3                 = userGenerator.generate
      val org1: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org2: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u1.id)
      val org3: Organization = organizationGenerator.generate.copy(archivedAt = None, createdBy = u2.id)
      val rl                 = rolesGenerator.generate
      for {
        _   <- withUser(u1)
        _   <- withUser(u2)
        _   <- withUser(u3)
        _   <- withOrganization(org1)
        _   <- withOrganization(org2)
        _   <- withOrganization(org3)
        r11 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u1.id, rl, None))
        r12 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u2.id, rl, None))
        r13 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org1.id, u3.id, rl, None))
        r23 <- rep.upsert(OrganizationUser(OrganizationUserId.random, org2.id, u3.id, rl, None))

        organizationIds = Some(Seq(org1.id, org2.id, org3.id))
        l0 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds))
        l1 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, organizationId = Some(org1.id)))
        l2 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, organizationId = Some(org2.id)))
        l3 <- rep.list(OrganizationUserFilters(organizationIds = organizationIds, organizationId = Some(org3.id)))
      } yield {

        l0 should containTheSameElementsAs(Seq(r11, r12, r13, r23))
        l1 should containTheSameElementsAs(Seq(r11, r12, r13))
        l2 should containTheSameElementsAs(Seq(r23))
        l3 should containTheSameElementsAs(Seq.empty)
      }
    }
  }

  "row locking" should {
    "emit SELECT FOR UPDATE only when explicitly requested" in {
      val unlocked = statements(rep.find(OrganizationUserFilters(userId = Some(userGenerator.generate.id), forUpdate = false)))
      val locked   = statements(rep.find(OrganizationUserFilters(userId = Some(userGenerator.generate.id), forUpdate = true)))

      unlocked.mkString(" ").toLowerCase.contains("for update") should beFalse
      locked.mkString(" ").toLowerCase.contains("for update") should beTrue
    }
  }

  private def statements(action: DBIO[Option[OrganizationUser]]): Iterable[String] =
    action.asInstanceOf[SqlAction[Option[OrganizationUser], NoStream, Effect.Read]].statements
}
