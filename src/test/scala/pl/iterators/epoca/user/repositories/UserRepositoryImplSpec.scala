package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.oauth.repositories.fixtures.{LegalServiceAccount, ServiceAccountFixture}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.OrganizationRepositoryImpl
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository.{UserFilters, UserSortBy}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.dbio.{DBIO, Effect, NoStream}
import slick.sql.SqlAction

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.util.Failure

class UserRepositoryImplSpec extends PgRepositorySpec with ServiceAccountFixture {

  private val concreteSut                                        = new UserRepositoryImpl()
  private val sut: UserRepository                                = concreteSut
  private val userIdentityRepository                             = new UserIdentityRepositoryImpl()
  private val organizationRepository                             = new OrganizationRepositoryImpl()
  private val serviceAccountRepository: ServiceAccountRepository = new ServiceAccountRepositoryImpl()

  val userGenerator: Generator[User]                 = allGenerators[User].maximal
  val userIdentityGenerator: Generator[UserIdentity] = allGenerators[UserIdentity].maximal
  val organizationGenerator: Generator[Organization] = allGenerators[Organization].maximal

  "human-only reads" should {
    "exclude a legal service account from every public repository read" in withDatabase {
      val human        = userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val organization = organizationGenerator.generate.copy(createdBy = human.id, archivedAt = None)
      val mailIdentity = userIdentityGenerator.generate.copy(userId = human.id, `type` = IdentityType.MailAddress)
      val phoneDigits  = f"${Math.abs(human.id.hashCode().toLong) % 1000000000L}%09d"
      val phoneIdentity =
        userIdentityGenerator.generate.copy(userId = human.id, `type` = IdentityType.Phone, value = UserIdentityValue(s"+48$phoneDigits"))

      for {
        _              <- sut.insert(human)
        _              <- organizationRepository.insert(organization)
        _              <- userIdentityRepository.insert(mailIdentity)
        _              <- userIdentityRepository.insert(phoneIdentity)
        serviceAccount <- withLegalServiceAccount(organization, human)

        foundById              <- sut.find(UserFilters(id = Some(serviceAccount.user.id)))
        foundArchivedInclusive <- sut.find(UserFilters(id = Some(serviceAccount.user.id), includeArchived = true))
        listedAll              <- sut.list(UserFilters())
        listedByIds            <- sut.list(UserFilters(ids = Some(Seq(human.id, serviceAccount.user.id)), includeArchived = true))
        listedByEmail          <- sut.list(UserFilters(email = Some(mailIdentity.mailAddress.get), includeArchived = true))
        listedByPhone          <- sut.list(UserFilters(phoneNumber = Some(phoneIdentity.phoneNumber.get), includeArchived = true))
        listedWithIdentities   <- sut.listWithIdentities(UserFilters(ids = Some(Seq(human.id, serviceAccount.user.id)), includeArchived = true))
      } yield {
        foundById should beNone
        foundArchivedInclusive should beNone
        listedAll.map(_.id) should not(contain(serviceAccount.user.id))
        listedByIds shouldEqual Seq(human)
        listedByEmail shouldEqual Seq(human)
        listedByPhone shouldEqual Seq(human)
        listedWithIdentities.map(_.id) shouldEqual Seq(human.id)
        UserFilters().productElementNames.toSeq should not(contain("accountType"))
      }
    }

    "exclude a legal service account from inherited find(id)" in {
      val result = withLegalServiceAccountPair { (_, serviceAccount) =>
        concreteSut.find(serviceAccount.user.id)
      }

      result should beNone
    }

    "exclude a legal service account from inherited get(id)" in {
      val result = withLegalServiceAccountPair { (_, serviceAccount) =>
        concreteSut.get(serviceAccount.user.id).asTry
      }

      result should beAnInstanceOf[Failure[_]]
    }

    "exclude legal service accounts from inherited list()" in {
      val (human, serviceAccount, result) = withLegalServiceAccountPair { (human, serviceAccount) =>
        concreteSut.list().map(users => (human, serviceAccount.user, users))
      }

      result should contain(human)
      result should not(contain(serviceAccount))
      result.forall(_.accountType == UserAccountType.Human) should beTrue
    }

    "exclude a legal service account from inherited list(ids)" in {
      val (human, result) = withLegalServiceAccountPair { (human, serviceAccount) =>
        concreteSut.list(Seq(human.id, serviceAccount.user.id)).map(human -> _)
      }

      result shouldEqual Seq(human)
    }

    "retain inherited reads for an archived Human" in withDatabase {
      val archivedHuman = userGenerator.generate.copy(
        archivedAt = Some(UserArchivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))),
        accountType = UserAccountType.Human
      )

      for {
        _      <- concreteSut.insert(archivedHuman)
        found  <- concreteSut.find(archivedHuman.id)
        gotten <- concreteSut.get(archivedHuman.id)
        all    <- concreteSut.list()
        listed <- concreteSut.list(Seq(archivedHuman.id))
      } yield {
        found shouldEqual Some(archivedHuman)
        gotten shouldEqual archivedHuman
        all should contain(archivedHuman)
        listed shouldEqual Seq(archivedHuman)
      }
    }
  }

  "internal service-account reads" should {
    "find only service accounts without exposing human users" in withDatabase {
      val human        = userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
      val organization = organizationGenerator.generate.copy(createdBy = human.id, archivedAt = None)

      for {
        _              <- sut.insert(human)
        _              <- organizationRepository.insert(organization)
        serviceAccount <- withLegalServiceAccount(organization, human)
        foundTechnical <- serviceAccountRepository.findById(serviceAccount.user.id)
        foundHuman     <- serviceAccountRepository.findById(human.id)
      } yield {
        foundTechnical shouldEqual Some(serviceAccount.user)
        foundHuman should beNone
      }
    }

    "honor the row-lock flag" in {
      val id       = UserId(UUID.randomUUID())
      val unlocked = statements(serviceAccountRepository.findById(id, forUpdate = false))
      val locked   = statements(serviceAccountRepository.findById(id, forUpdate = true))

      unlocked.mkString(" ").toLowerCase must not(contain("for update"))
      locked.mkString(" ").toLowerCase must contain("for update")
    }
  }

  private def withLegalServiceAccountPair[A](action: (User, LegalServiceAccount) => DBIO[A]): A = {
    val human        = userGenerator.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
    val organization = organizationGenerator.generate.copy(createdBy = human.id, archivedAt = None)

    withDatabase {
      for {
        _              <- concreteSut.insert(human)
        _              <- organizationRepository.insert(organization)
        serviceAccount <- withLegalServiceAccount(organization, human)
        result         <- action(human, serviceAccount)
      } yield result
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      for {
        r1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id))

        l0  <- sut.list(UserFilters(ids = ids))
        l1  <- sut.list(UserFilters(id = Some(r1.id)))
        l2  <- sut.list(UserFilters(id = Some(r2.id)))
        l3  <- sut.list(UserFilters(id = Some(r3.id)))
        l12 <- sut.list(UserFilters(ids = Some(List(r1.id, r2.id))))

        f1 <- sut.find(UserFilters(id = Some(r1.id)))
        f2 <- sut.find(UserFilters(id = Some(r2.id)))
        f3 <- sut.find(UserFilters(id = Some(r3.id)))

        ru1 = r1.copy(firstName = userGenerator.generate.firstName)
        ru2 = r2.copy(firstName = userGenerator.generate.firstName)

        _ <- sut.update(ru1)
        _ <- sut.update(ru2)
        r3a = r3.copy(archivedAt = Some(UserArchivedAt(Instant.ofEpochMilli(System.currentTimeMillis()))))
        _ <- sut.update(r3a)

        lu0  <- sut.list(UserFilters(ids = ids))
        lu0i <- sut.list(UserFilters(ids = ids, includeArchived = true))
        lu1  <- sut.list(UserFilters(id = Some(r1.id)))
        lu2  <- sut.list(UserFilters(id = Some(r2.id)))
        lu3  <- sut.list(UserFilters(id = Some(r3.id)))

        fu1 <- sut.find(UserFilters(id = Some(r1.id)))
        fu2 <- sut.find(UserFilters(id = Some(r2.id)))
        fu3 <- sut.find(UserFilters(id = Some(r3.id)))
      } yield {
        l0 should containTheSameElementsAs(Seq(r1, r2, r3))
        l1 should containTheSameElementsAs(Seq(r1))
        l2 should containTheSameElementsAs(Seq(r2))
        l3 should containTheSameElementsAs(Seq(r3))
        l12 should containTheSameElementsAs(Seq(r1, r2))

        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)

        lu0 should containTheSameElementsAs(Seq(ru1, ru2))
        lu0i should containTheSameElementsAs(Seq(ru1, ru2, r3a))
        lu1 should containTheSameElementsAs(Seq(ru1))
        lu2 should containTheSameElementsAs(Seq(ru2))
        lu3 should containTheSameElementsAs(Seq.empty)

        fu1 shouldEqual Some(ru1)
        fu2 shouldEqual Some(ru2)
        fu3 shouldEqual None
      }
    }
  }
  "filter by provided params" should {
    "email" in withDatabase {
      for {
        o1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))

        i1 = userIdentityGenerator.generate.copy(userId = o1.id, `type` = IdentityType.MailAddress)
        i2 = userIdentityGenerator.generate.copy(userId = o2.id, `type` = IdentityType.MailAddress)
        i3 = userIdentityGenerator.generate.copy(userId = o3.id, `type` = IdentityType.MailAddress)

        _ <- userIdentityRepository.insert(i1)
        _ <- userIdentityRepository.insert(i2)
        _ <- userIdentityRepository.insert(i3)

        l1 <- sut.list(UserFilters(email = Some(i1.mailAddress.get)))
        l2 <- sut.list(UserFilters(email = Some(MailAddress(i2.mailAddress.get.toLowerCase))))
        l3 <- sut.list(UserFilters(email = Some(i3.mailAddress.get)))
      } yield {
        l1 should containTheSameElementsAs(Seq(o1))
        l2 should containTheSameElementsAs(Seq(o2))
        l3 should containTheSameElementsAs(Seq(o3))
      }
    }
    "phoneNumber" in withDatabase {
      for {
        o1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))

        i1 = userIdentityGenerator.generate.copy(userId = o1.id, `type` = IdentityType.Phone)
        i2 = userIdentityGenerator.generate.copy(userId = o2.id, `type` = IdentityType.Phone)
        i3 = userIdentityGenerator.generate.copy(userId = o3.id, `type` = IdentityType.Phone)

        _ <- userIdentityRepository.insert(i1)
        _ <- userIdentityRepository.insert(i2)
        _ <- userIdentityRepository.insert(i3)

        l1 <- sut.list(UserFilters(phoneNumber = Some(i1.phoneNumber.get)))
        l2 <- sut.list(UserFilters(phoneNumber = Some(i2.phoneNumber.get)))
        l3 <- sut.list(UserFilters(phoneNumber = Some(i3.phoneNumber.get)))
      } yield {
        l1 should containTheSameElementsAs(Seq(o1))
        l2 should containTheSameElementsAs(Seq(o2))
        l3 should containTheSameElementsAs(Seq(o3))
      }
    }
    "includeArchived" in withDatabase {
      for {
        r1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id))

        l0 <- sut.list(UserFilters(ids = ids))
        l1 <- sut.list(UserFilters(id = Some(r1.id)))
        l2 <- sut.list(UserFilters(id = Some(r2.id)))

        li0 <- sut.list(UserFilters(ids = ids, includeArchived = true))
        li1 <- sut.list(UserFilters(id = Some(r1.id), includeArchived = true))
        li2 <- sut.list(UserFilters(id = Some(r2.id), includeArchived = true))

        now = Instant.ofEpochMilli(System.currentTimeMillis())
        rd1 = r1.copy(archivedAt = Some(UserArchivedAt(now)))
        _ <- sut.update(rd1)

        ld0 <- sut.list(UserFilters(ids = ids))
        ld1 <- sut.list(UserFilters(id = Some(r1.id)))
        ld2 <- sut.list(UserFilters(id = Some(r2.id)))

        ldi0 <- sut.list(UserFilters(ids = ids, includeArchived = true))
        ldi1 <- sut.list(UserFilters(id = Some(r1.id), includeArchived = true))
        ldi2 <- sut.list(UserFilters(id = Some(r2.id), includeArchived = true))
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
    "page size and number" in withDatabase {
      for {
        r1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r4 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r5 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id, r4.id, r5.id))

        l1 <- sut.list(UserFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 4, number = 1))))
        l2 <- sut.list(UserFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))))
        l3 <- sut.list(UserFilters(ids = ids, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))))
      } yield {
        l1.size shouldEqual 4
        l2.size shouldEqual 2
        l3.size shouldEqual 2

        l1 shouldEqual (l2 ++ l3)
      }
    }
  }

  "sortBy provided params" should {
    "CreatedAtAsc (default) CreatedAtDesc" in withDatabase {
      for {
        r1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        r3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(r1.id, r2.id, r3.id))

        lDefault <- sut.list(UserFilters(ids = ids))
        lAsc     <- sut.list(UserFilters(ids = ids, sortBy = Some(UserSortBy.CreatedAtAsc)))
        lDesc    <- sut.list(UserFilters(ids = ids, sortBy = Some(UserSortBy.CreatedAtDesc)))
      } yield {
        lDefault shouldEqual lAsc
        lAsc should containTheSameElementsAs(Seq(r1, r2, r3))
        lAsc must beSorted(Ordering.by[User, UserCreatedAt](_.createdAt)).setMessage("not sorted by created at")
        lDesc should containTheSameElementsAs(Seq(r1, r2, r3))
        lDesc must beSorted(Ordering.by[User, UserCreatedAt](_.createdAt).reverse).setMessage("not sorted by created at reverse")
      }
    }
  }

  "list with identities" should {
    "return users with identities" in withDatabase {
      for {
        o1 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o2 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        o3 <- sut.insert(userGenerator.generate.copy(archivedAt = None))
        ids = Some(Seq(o1.id, o2.id, o3.id))

        i1 = userIdentityGenerator.generate.copy(userId = o1.id, `type` = IdentityType.MailAddress)
        i2 = userIdentityGenerator.generate.copy(userId = o1.id, `type` = IdentityType.Phone)
        i3 = userIdentityGenerator.generate.copy(userId = o2.id, `type` = IdentityType.MailAddress)

        _ <- userIdentityRepository.insert(i1)
        _ <- userIdentityRepository.insert(i2)
        _ <- userIdentityRepository.insert(i3)

        l1 <- sut.listWithIdentities(UserFilters(ids = ids))
        l2 <- sut.listWithIdentities(UserFilters(id = Some(o1.id)))

      } yield {
        l1 should containTheSameElementsAs(
          Seq(UserWithIdentities(o1, Seq(i1, i2)), UserWithIdentities(o2, Seq(i3)), UserWithIdentities(o3, Seq.empty))
        )
        l2 should containTheSameElementsAs(Seq(UserWithIdentities(o1, Seq(i1, i2))))
      }
    }
  }

  private def statements(action: DBIO[Option[User]]): Iterable[String] =
    action.asInstanceOf[SqlAction[Option[User], NoStream, Effect.Read]].statements
}
