package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepositoryImpl
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.dbio.DBIO

import java.time.Instant

class InvitationRepositoryImplSpec
    extends PgRepositorySpec
    with OrganizationFixture
    with OrganizationUserFixture
    with UserFixture
    with ServiceAccountFixture {

  private val rep: InvitationRepository = new InvitationRepositoryImpl()

  def generateInvitation(organization: Organization, deleted: Boolean = false): Invitation = {
    val invitationGenerator: Generator[Invitation] = allGenerators[Invitation].maximal
    val invitation                                 = invitationGenerator.generate.copy(organizationId = organization.id)
    if (deleted) invitation
    else invitation.copy(archivedAt = None)
  }
  val organizationGenerator: Generator[Organization] = allGenerators[Organization].maximal
  val userGenerator: Generator[User]                 = allGenerators[User].maximal

  "human invitation reads" should {
    "exclude invitations and totals for a legal service-account membership before paging" in withDatabase {
      val human        = userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val organization = organizationGenerator.generate.copy(createdBy = human.id, archivedAt = None)

      for {
        _              <- withUser(human)
        _              <- withOrganization(organization)
        serviceAccount <- withLegalServiceAccount(organization, human)
        _              <- rep.insert(generateInvitation(organization))
        _              <- rep.insert(generateInvitation(organization))
        filter = InvitationFilters(userId = Some(serviceAccount.user.id))
        listed     <- rep.list(filter)
        count      <- rep.count(filter)
        firstPage  <- rep.list(filter.copy(pageSizeAndNumber = Some(PageSizeAndNumber(size = 1, number = 1))))
        secondPage <- rep.list(filter.copy(pageSizeAndNumber = Some(PageSizeAndNumber(size = 1, number = 2))))
      } yield {
        listed should beEmpty
        count shouldEqual 0
        firstPage should beEmpty
        secondPage should beEmpty
      }
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _ <- withOrganization(org)

        r1 <- rep.insert(generateInvitation(org))
        r2 <- rep.insert(generateInvitation(org))
        r3 <- rep.insert(generateInvitation(org))

        f1 <- rep.find(InvitationFilters(id = Some(r1.id)))
        f2 <- rep.find(InvitationFilters(id = Some(r2.id)))
        f3 <- rep.find(InvitationFilters(id = Some(r3.id)))

        l0 <- rep.list(InvitationFilters())
        l1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        l2 <- rep.list(InvitationFilters(id = Some(r2.id)))
        l3 <- rep.list(InvitationFilters(id = Some(r3.id)))

        ru1 = r1.copy(identityValue = generateInvitation(org).identityValue)
        ru2 = r2.copy(identityValue = generateInvitation(org).identityValue)

        _ <- rep.update(ru1)
        _ <- rep.update(ru2)

        lu0 <- rep.list(InvitationFilters())
        lu1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        lu2 <- rep.list(InvitationFilters(id = Some(r2.id)))
        lu3 <- rep.list(InvitationFilters(id = Some(r3.id)))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd2 = r2.copy(archivedAt = Some(InvitationArchivedAt(now)))
        rd3 = r3.copy(archivedAt = Some(InvitationArchivedAt(now)))

        _ <- rep.update(rd2)
        _ <- rep.update(rd3)

        ld0 <- rep.list(InvitationFilters())
        ld1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        ld2 <- rep.list(InvitationFilters(id = Some(r2.id)))
        ld3 <- rep.list(InvitationFilters(id = Some(r3.id)))

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
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        r1 <- rep.insert(generateInvitation(org))
        r2 <- rep.insert(generateInvitation(org))
        r3 <- rep.insert(generateInvitation(org))

        l1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        l2 <- rep.list(InvitationFilters(id = Some(r2.id)))
        l3 <- rep.list(InvitationFilters(id = Some(r3.id)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "organizationId" in withDatabase {
      for {
        o1 <- withOrganization()
        o2 <- withOrganization()
        r1 <- rep.insert(generateInvitation(o1))
        r2 <- rep.insert(generateInvitation(o1))
        r3 <- rep.insert(generateInvitation(o2))

        l1 <- rep.list(InvitationFilters(organizationId = Some(o1.id)))
        l2 <- rep.list(InvitationFilters(organizationId = Some(o2.id)))
      } yield {

        l1 should containTheSameElementsAs(Seq(r1, r2))
        l2 should containTheSameElementsAs(Seq(r3))
      }
    }

    "acceptableOn" in withDatabase {
      val t  = Instant.now
      val t1 = t.plusSeconds(1)
      val t2 = t.plusSeconds(2)
      val t3 = t.plusSeconds(3)
      val t4 = t.plusSeconds(4)
      val t5 = t.plusSeconds(5)

      for {
        o  <- withOrganization()
        r1 <- rep.insert(generateInvitation(o).copy(acceptAt = None, rejectAt = None, expireAt = Some(InvitationExpireAt(t2))))
        r2 <- rep.insert(generateInvitation(o).copy(acceptAt = None, rejectAt = None, expireAt = Some(InvitationExpireAt(t4))))
        r3 <- rep.insert(generateInvitation(o).copy(acceptAt = None, rejectAt = None, expireAt = None))
        _  <- rep.insert(generateInvitation(o).copy(acceptAt = None))
        _  <- rep.insert(generateInvitation(o).copy(rejectAt = None))

        l1 <- rep.list(InvitationFilters(acceptableOn = Some(t1)))
        l2 <- rep.list(InvitationFilters(acceptableOn = Some(t3)))
        l3 <- rep.list(InvitationFilters(acceptableOn = Some(t5)))
      } yield {
        l1 should containTheSameElementsAs(Seq(r1, r2, r3))
        l2 should containTheSameElementsAs(Seq(r2, r3))
        l3 should containTheSameElementsAs(Seq(r3))
      }
    }

    "userId requires a confirmed own recipient identity, never organization membership" in withDatabase {
      for {
        user <- withUser(userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
        org  <- withOrganization(organizationGenerator.generate.copy(createdBy = user.id, archivedAt = None))
        _    <- withCreatorOrganizationUser(org, Nil)
        _ <-
          rep.insert(generateInvitation(org).copy(identityType = IdentityType.MailAddress, identityValue = UserIdentityValue("other@example.test")))
        own <-
          rep.insert(generateInvitation(org).copy(identityType = IdentityType.MailAddress, identityValue = UserIdentityValue(" OWN@EXAMPLE.TEST ")))
        _ <-
          rep.insert(
            generateInvitation(org).copy(identityType = IdentityType.MailAddress, identityValue = UserIdentityValue("unconfirmed@example.test"))
          )
        phone <- rep.insert(generateInvitation(org).copy(identityType = IdentityType.Phone, identityValue = UserIdentityValue("+48123123123")))
        _ <- DBIO.sequence(
               Seq(
                 (IdentityType.MailAddress, "own@example.test", true),
                 (IdentityType.MailAddress, "unconfirmed@example.test", false),
                 (IdentityType.Phone, "+48123123123", true)
               ).map { case (kind, value, confirmed) =>
                 new UserIdentityRepositoryImpl().insert(
                   allGenerators[UserIdentity].normal.generate
                     .copy(userId = user.id, `type` = kind, value = UserIdentityValue(value), confirmed = UserIdentityConfirmed(confirmed))
                 )
               }
             )
        listed <- rep.list(InvitationFilters(userId = Some(user.id)))
        count  <- rep.count(InvitationFilters(userId = Some(user.id)))
      } yield {
        listed should containTheSameElementsAs(Seq(own, phone))
        count shouldEqual 2
      }
    }

    "bound SAML recipient filtering requires the current organization, issuer and epoch" in withDatabase {
      import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
      import pl.iterators.epoca.saml.domain.{SamlConfig, SamlDomain}
      import pl.iterators.epoca.utils.db.BaseTable.driver.api._
      for {
        user <- withUser(userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human))
        org  <- withOrganization(organizationGenerator.generate.copy(archivedAt = None))
        invitation <-
          rep.insert(generateInvitation(org).copy(identityType = IdentityType.MailAddress, identityValue = UserIdentityValue("bound@example.test")))
        _ <- new pl.iterators.epoca.saml.repositories.SamlConfigRepositoryImpl().upsert(
               allGenerators[SamlConfig].normal.generate
                 .copy(organizationId = org.id, isEnabled = SamlDomain.SamlIsEnabled(true), idpEntityId = SamlDomain.SamlIdpEntityId("issuer"))
             )
        _ <-
          sqlu"INSERT INTO saml_identity_binding(organization_id,trust_epoch,issuer,subject,user_id,canonical_email,verified_at,proof) VALUES (${org.id.toString}::uuid,1,'issuer','subject',${user.id.toString}::uuid,'bound@example.test',now(),'{}')"
        proof = BoundSaml(org.id, 1, "issuer", "subject")
        without <- rep.list(InvitationFilters(userId = Some(user.id)))
        matched <- rep.list(InvitationFilters(userId = Some(user.id), boundSaml = Some(proof)))
        wrong   <- rep.list(InvitationFilters(userId = Some(user.id), boundSaml = Some(proof.copy(issuer = "wrong"))))
        _       <- sqlu"UPDATE saml_config SET trust_epoch=2 WHERE organization_id=${org.id.toString}::uuid"
        stale   <- rep.list(InvitationFilters(userId = Some(user.id), boundSaml = Some(proof)))
      } yield { without must beEmpty; matched mustEqual Seq(invitation); wrong must beEmpty; stale must beEmpty }
    }

    "page size and number" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _ <- withOrganization(org)
        _ <- rep.insert(generateInvitation(org))
        _ <- rep.insert(generateInvitation(org))
        _ <- rep.insert(generateInvitation(org))
        _ <- rep.insert(generateInvitation(org))
        _ <- rep.insert(generateInvitation(org))

        l1 <- rep.list(InvitationFilters(pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        l2 <- rep.list(InvitationFilters(pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))
        l3 <- rep.list(InvitationFilters(pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))))
      } yield {
        l1.size shouldEqual 4
        l2.size shouldEqual 2
        l3.size shouldEqual 2

        l1 shouldEqual (l2 ++ l3)
      }
    }

    "includeArchived" in withDatabase {
      val org = organizationGenerator.generate
      for {
        _  <- withOrganization(org)
        r1 <- rep.insert(generateInvitation(org))
        r2 <- rep.insert(generateInvitation(org))

        l0 <- rep.list(InvitationFilters(organizationId = Some(org.id)))
        l1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        l2 <- rep.list(InvitationFilters(id = Some(r2.id)))

        li0 <- rep.list(InvitationFilters(organizationId = Some(org.id), includeArchived = true))
        li1 <- rep.list(InvitationFilters(id = Some(r1.id), includeArchived = true))
        li2 <- rep.list(InvitationFilters(id = Some(r2.id), includeArchived = true))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd1 = r1.copy(archivedAt = Some(InvitationArchivedAt(now)))
        _ <- rep.update(rd1)

        ld0 <- rep.list(InvitationFilters(organizationId = Some(org.id)))
        ld1 <- rep.list(InvitationFilters(id = Some(r1.id)))
        ld2 <- rep.list(InvitationFilters(id = Some(r2.id)))

        ldi0 <- rep.list(InvitationFilters(organizationId = Some(org.id), includeArchived = true))
        ldi1 <- rep.list(InvitationFilters(id = Some(r1.id), includeArchived = true))
        ldi2 <- rep.list(InvitationFilters(id = Some(r2.id), includeArchived = true))
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
  "count" should {
    "properly handle filters and pagesize" in withDatabase {
      val org1 = organizationGenerator.generate
      val org2 = organizationGenerator.generate
      for {
        _ <- withOrganization(org1)
        _ <- withOrganization(org2)

        _ <- rep.insert(generateInvitation(org1))
        _ <- rep.insert(generateInvitation(org1))
        _ <- rep.insert(generateInvitation(org1))
        _ <- rep.insert(generateInvitation(org2))
        _ <- rep.insert(generateInvitation(org2))

        c1  <- rep.count(InvitationFilters())
        c11 <- rep.count(InvitationFilters(pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        c12 <- rep.count(InvitationFilters(pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))

        c2  <- rep.count(InvitationFilters(organizationId = Some(org1.id)))
        c21 <- rep.count(InvitationFilters(organizationId = Some(org1.id), pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 10))))

        c3 <- rep.count(InvitationFilters(organizationId = Some(org2.id)))
      } yield {
        c1 shouldEqual 5
        c11 shouldEqual 5
        c12 shouldEqual 5

        c2 shouldEqual 3
        c21 shouldEqual 3

        c3 shouldEqual 2
      }
    }
  }

}
