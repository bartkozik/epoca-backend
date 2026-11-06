package pl.iterators.epoca.client.services

import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.client.repositories.{ClientRepository, ClientRepositoryImpl}
import pl.iterators.epoca.client.services.ClientListService.ClientListResult
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.repositories.ProjectRepositoryImpl
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserIsSuperAdmin}
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.transactor.IsolationLevel
import pl.iterators.epoca.utils.{AuthContextProvider, PgRepositorySpec}
import slick.dbio.DBIO

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ClientListServiceRaceSpec extends PgRepositorySpec {
  private implicit val ioRuntime: IORuntime = IORuntime.global

  private val userRepository             = new UserRepositoryImpl()
  private val organizationRepository     = new OrganizationRepositoryImpl()
  private val organizationUserRepository = new OrganizationUserRepositoryImpl()
  private val projectRepository          = new ProjectRepositoryImpl()
  private val realClientRepository       = new ClientRepositoryImpl()
  private val clientDtoService           = new ClientDtoService(projectRepository)

  private val userGenerator: Generator[User]                 = allGenerators[User].normal
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal
  private val clientGenerator: Generator[Client]             = allGenerators[Client].normal

  private lazy val writerDb = Database.forURL(
    url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
    user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
    password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(getClass.getCanonicalName + ".writer", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
    prop = new Properties()
  )

  private def runOn[A](database: Database, action: DBIO[A]): A = Await.result(database.run(action), Duration.Inf)

  private class ReadCommittedTransactor extends PostgresIOTransactor(db) {
    override def executeTransactionally[T](level: IsolationLevel)(f: => DBIO[T]): cats.effect.IO[T] =
      super.executeTransactionally(IsolationLevel.ReadCommitted)(f)
  }

  private class RacingClientRepository(onBetween: => Client) extends ClientRepository {
    private val fired                                         = new AtomicBoolean(false)
    override def insert(client: Client): DBIO[Client]         = realClientRepository.insert(client)
    override def update(client: Client): DBIO[Option[Client]] = realClientRepository.update(client)
    override def lockForMutation(clientId: pl.iterators.epoca.client.domain.ClientDomain.ClientId): DBIO[Unit] =
      realClientRepository.lockForMutation(clientId)
    override def find(filter: ClientFilters): DBIO[Option[Client]] = realClientRepository.find(filter)
    override def list(filter: ClientFilters): DBIO[Seq[Client]]    = realClientRepository.list(filter)
    override def count(filter: ClientFilters): DBIO[Int] =
      DBIO
        .successful(())
        .flatMap { _ =>
          if (fired.compareAndSet(false, true)) {
            val racing = onBetween
            DBIO.from(writerDb.run(realClientRepository.insert(racing))).map(_ => ())
          } else DBIO.successful(())
        }
        .flatMap(_ => realClientRepository.count(filter))
  }

  private case class Fixture(
    admin: pl.iterators.epoca.auth.domain.AuthContext,
    organization: Organization,
    seeded: Seq[Client],
    racing: Client)

  private def fixture(): Fixture = {
    val owner = userGenerator.generate.copy(accountType = UserAccountType.Human, archivedAt = None, isSuperAdmin = UserIsSuperAdmin(true))
    val org   = organizationGenerator.generate.copy(createdBy = owner.id, archivedAt = None)
    runOn(db, userRepository.insert(owner))
    runOn(db, organizationRepository.insert(org))
    def newClient() =
      clientGenerator.generate.copy(organizationId = org.id, createdBy = owner.id, modifiedBy = owner.id, archivedAt = None, archivedBy = None)
    val seeded = Seq(newClient(), newClient())
    seeded.foreach(c => runOn(db, realClientRepository.insert(c)))
    Fixture(AuthContextProvider.admin.copy(id = owner.id, isSuperAdmin = owner.isSuperAdmin), org, seeded, newClient())
  }

  private def service(transactor: PostgresIOTransactor, repo: ClientRepository) =
    new ClientListService(transactor, organizationRepository, organizationUserRepository, projectRepository, repo, clientDtoService)

  private def listOk(sut: ClientListService, f: Fixture) =
    sut.list(f.admin, f.organization.slug, ClientFilters()).unsafeRunSync() match {
      case ClientListResult.Ok(page) => page
      case other                     => sys.error(s"expected Ok, got $other")
    }

  "ClientListService.list with a concurrent committed insert between list and count" should {
    "return a count consistent with the listed results (RepeatableRead snapshot)" in {
      val f   = fixture()
      val sut = service(new PostgresIOTransactor(db), new RacingClientRepository(f.racing))

      val page = listOk(sut, f)
      val afterwards = listOk(service(new PostgresIOTransactor(db), realClientRepository), f)

      (page.results.map(_.id).toSet shouldEqual f.seeded.map(_.id).toSet) and
        (page.count shouldEqual page.results.length) and
        (page.count shouldEqual 2) and
        (afterwards.count shouldEqual 3) and
        (afterwards.results.map(_.id) should contain(f.racing.id))
    }

    "show count != results.length under READ COMMITTED (negative control proving the race is real)" in {
      val f   = fixture()
      val sut = service(new ReadCommittedTransactor, new RacingClientRepository(f.racing))

      val page = listOk(sut, f)

      (page.results.length shouldEqual 2) and (page.count shouldEqual 3)
    }
  }
}
