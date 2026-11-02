package pl.iterators.epoca.organization.services

import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationUserId, OrganizationUserRole}
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.tables.{OrganizationTable, OrganizationUserTable}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepository, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.organization.services.OrganizationCreateService.{OrganizationCreateRequest, OrganizationCreateResult}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.user.repositories.tables.UserTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import slick.dbio.DBIO

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class OrganizationCreateServiceIntegrationSpec extends PgRepositorySpec with ServiceAccountFixture {
  private implicit val ioRuntime: IORuntime = IORuntime.global

  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val organizationTable          = TableQuery[OrganizationTable]
  private val organizationUserTable      = TableQuery[OrganizationUserTable]
  private val userTable                  = TableQuery[UserTable]
  private val transactor                 = new PostgresIOTransactor(db)
  private val sut = new OrganizationCreateService(
    OrganizationCreateService.Config(organizationLimitUserCanCreate = None),
    transactor,
    userRepository,
    organizationRepository,
    organizationUserRepository
  )

  private val userGenerator: Generator[User]                         = allGenerators[User].normal
  private val organizationGenerator: Generator[Organization]         = allGenerators[Organization].normal
  private val requestGenerator: Generator[OrganizationCreateRequest] = allGenerators[OrganizationCreateRequest].normal

  private case object MembershipUpsertFailure extends RuntimeException("forced membership upsert failure")

  private final class FailingOrganizationUserRepository(delegate: OrganizationUserRepository) extends OrganizationUserRepository {
    override def upsert(organizationUser: OrganizationUser): DBIO[OrganizationUser]    = DBIO.failed(MembershipUpsertFailure)
    override def find(filter: OrganizationUserFilters): DBIO[Option[OrganizationUser]] = delegate.find(filter)
    override def list(filter: OrganizationUserFilters): DBIO[Seq[OrganizationUser]]    = delegate.list(filter)
    override def delete(id: OrganizationUserId, when: Instant): DBIO[Int]              = delegate.delete(id, when)
  }

  "create" should {
    "reject a service-account actor without leaving an orphan organization" in {
      val human            = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val baseOrganization = organizationGenerator.generate.copy(createdBy = human.id, archivedAt = None)
      val request          = requestGenerator.generate

      val serviceAccount = run((for {
        _       <- userRepository.insert(human)
        _       <- organizationRepository.insert(baseOrganization)
        fixture <- withLegalServiceAccount(baseOrganization, human)
      } yield fixture).transactionally)
      val authContext = AuthContextProvider.user.copy(id = serviceAccount.user.id, isSuperAdmin = UserIsSuperAdmin(false), identities = Nil)

      try {
        val result = sut.create(authContext, request).attempt.unsafeRunSync()
        val found = run(
          organizationRepository.find(
            pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters(slug = Some(request.slug), includeArchived = true)
          )
        )

        (found should beNone) and (result.map(_.toString) shouldEqual Right("OperationNotPermitted"))
      } finally {
        val _ = run(organizationTable.filter(_.slug === request.slug).delete)
      }
    }

    "return the same unavailable result for a missing actor without writing an organization" in {
      val request     = requestGenerator.generate
      val missingUser = UserId(UUID.randomUUID())
      val authContext = AuthContextProvider.user.copy(id = missingUser, isSuperAdmin = UserIsSuperAdmin(false), identities = Nil)

      val result = sut.create(authContext, request).attempt.unsafeRunSync()
      val found = run(
        organizationRepository.find(
          pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters(slug = Some(request.slug), includeArchived = true)
        )
      )

      (found should beNone) and (result.map(_.toString) shouldEqual Right("OperationNotPermitted"))
    }

    "roll back the organization when owner membership upsert fails" in {
      val human       = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val request     = requestGenerator.generate
      val authContext = AuthContextProvider.user.copy(id = human.id, isSuperAdmin = UserIsSuperAdmin(false), identities = Nil)
      val failingSut = new OrganizationCreateService(
        OrganizationCreateService.Config(organizationLimitUserCanCreate = None),
        transactor,
        userRepository,
        organizationRepository,
        new FailingOrganizationUserRepository(organizationUserRepository)
      )

      run(userRepository.insert(human))
      try {
        val result = failingSut.create(authContext, request).attempt.unsafeRunSync()
        val found = run(
          organizationRepository.find(
            pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters(slug = Some(request.slug), includeArchived = true)
          )
        )

        (found should beNone) and (result shouldEqual Left(MembershipUpsertFailure))
      } finally
        cleanup(request, human.id)
    }

    "create an organization and owner membership atomically for a Human actor" in {
      val human       = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val request     = requestGenerator.generate
      val authContext = AuthContextProvider.user.copy(id = human.id, isSuperAdmin = UserIsSuperAdmin(false), identities = Nil)

      run(userRepository.insert(human))
      try {
        val result = sut.create(authContext, request).unsafeRunSync()
        val created = result match {
          case OrganizationCreateResult.Created(organization) => organization
          case other                                          => throw new AssertionError(s"expected Created, got $other")
        }
        val found = run(
          organizationRepository.find(
            pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters(slug = Some(request.slug), includeArchived = true)
          )
        )
        val memberships = run(
          organizationUserRepository.list(OrganizationUserFilters(organizationId = Some(created.id), userId = Some(human.id), includeArchived = true))
        )

        (found shouldEqual Some(created)) and
          (memberships should have size 1) and
          (memberships.head.roles.contains(OrganizationUserRole.Owner) should beTrue)
      } finally
        cleanup(request, human.id)
    }
  }

  private def cleanup(request: OrganizationCreateRequest, userId: UserId): Unit = {
    val cleanupAction = for {
      organizationIds <- organizationTable.filter(_.slug === request.slug).map(_.id).result
      _               <- organizationUserTable.filter(_.organizationId.inSet(organizationIds)).delete
      _               <- organizationTable.filter(_.id.inSet(organizationIds)).delete
      _               <- userTable.filter(_.id === userId).delete
    } yield ()
    val _ = run(cleanupAction.transactionally)
  }

  private def run[A](action: DBIO[A]): A = Await.result(db.run(action), Duration.Inf)
}
