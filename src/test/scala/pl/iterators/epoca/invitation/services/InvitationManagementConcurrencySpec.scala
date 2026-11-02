package pl.iterators.epoca.invitation.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.specs2.mutable.Specification
import pl.iterators.epoca.invitation.repositories.{InvitationOnboardingRepositoryImpl, InvitationRepositoryImpl}
import pl.iterators.epoca.invitation.services.InvitationManagementService._
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.{EmailIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.{Properties, UUID}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class InvitationManagementConcurrencySpec extends Specification with Generators {
  sequential
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def database = Database.forURL(
    sys.env.getOrElse("TEST_DB_URL", "epoca"),
    sys.env.getOrElse("TEST_DB_USER", "postgres"),
    sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor("invitation-management-race", 3, 3, 20, maxConnections = 3),
    prop = new Properties()
  )
  private def await[A](value: Future[A]): A = Await.result(value, 30.seconds)
  private def body(email: String) = Json.obj(
    "firstName" -> Json.fromString("Anna"),
    "lastName"  -> Json.fromString("Test"),
    "email"     -> Json.fromString(email),
    "role"      -> Json.fromString("Employee"),
    "locale"    -> Json.fromString("pl")
  )
  private def gate(latch: CountDownLatch, pids: java.util.Set[Int]): DBIO[Unit] = SimpleDBIO { ctx =>
    val statement = ctx.connection.createStatement()
    try {
      val rows = statement.executeQuery("SELECT pg_backend_pid()")
      try { require(rows.next()); pids.add(rows.getInt(1)) }
      finally rows.close()
    } finally statement.close()
    latch.countDown()
    require(latch.await(20, TimeUnit.SECONDS), "Both transactions must reach the barrier")
  }
  Seq("two Owners", "same command", "same command different email", "resend retry", "resend changed payload", "resend different invitation").foreach {
    scenario =>
      s"serialize $scenario without extra invitations, audit or deliveries" in {
        val db          = database
        val userRepo    = new UserRepositoryImpl
        val orgRepo     = new OrganizationRepositoryImpl
        val memberships = new OrganizationUserRepositoryImpl
        val first       = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
        val second      = allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human)
        val org         = allGenerators[Organization].normal.generate.copy(archivedAt = None)
        val latch       = new CountDownLatch(2)
        val pids        = java.util.concurrent.ConcurrentHashMap.newKeySet[Int]()
        val tx = new PostgresIOTransactor(db) {
          override def executeTransactionally[A](action: => DBIO[A]): IO[A] = super.executeTransactionally(gate(latch, pids).flatMap(_ => action))
        }
        val isResend = scenario.startsWith("resend")
        val now      = Instant.parse("2026-10-01T10:00:00Z")
        def management(transactor: PostgresIOTransactor, at: Instant) = new InvitationManagementService(
          transactor,
          new InvitationOnboardingRepositoryImpl,
          new InvitationRepositoryImpl,
          new EmailIdentityRepositoryImpl,
          new InvitationAccessGuard,
          new InvitationSecretService,
          Clock.fixed(at, ZoneOffset.UTC)
        )
        val service        = management(tx, now.plusSeconds(60))
        val initialService = management(new PostgresIOTransactor(db), now)
        val firstKey       = UUID.randomUUID()
        val secondKey      = if (scenario == "two Owners") UUID.randomUUID() else firstKey
        val firstEmail     = s"race-${UUID.randomUUID()}@example.test"
        val secondEmail    = if (scenario == "same command different email") s"other-${UUID.randomUUID()}@example.test" else firstEmail
        await(
          db.run(
            DBIO
              .seq(
                userRepo.insert(first),
                userRepo.insert(second),
                orgRepo.insert(org),
                memberships.insertPrepared(OrganizationUser(OrganizationUserId.random, org.id, first.id, Seq(OrganizationUserRole.Owner), None)),
                memberships.insertPrepared(OrganizationUser(OrganizationUserId.random, org.id, second.id, Seq(OrganizationUserRole.Owner), None))
              )
              .transactionally
          )
        )
        try {
          val initialCount = if (!isResend) 0 else if (scenario == "resend different invitation") 2 else 1
          val initial = Vector.tabulate(initialCount) { _ =>
            val result =
              await(initialService.create(first.id, org.slug, UUID.randomUUID(), body(s"initial-${UUID.randomUUID()}@example.test")).unsafeToFuture())
            val id = result.asInstanceOf[Ok].dto.hcursor.get[String]("id").toOption.get
            pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId(UUID.fromString(id))
          }
          val resendBody = Json.obj("expectedRevision" -> Json.fromString("0"))
          val otherBody =
            if (scenario == "resend changed payload") resendBody.deepMerge(Json.obj("firstName" -> Json.fromString("Changed"))) else resendBody
          val left = (if (isResend) service.resend(first.id, org.slug, initial.head, firstKey, resendBody)
                      else service.create(first.id, org.slug, firstKey, body(firstEmail))).unsafeToFuture()
          val right = (if (isResend) service.resend(first.id, org.slug, initial.last, secondKey, otherBody)
                       else service.create(if (scenario == "two Owners") second.id else first.id, org.slug, secondKey, body(secondEmail)))
            .unsafeToFuture()
          val results   = Vector(await(left), await(right))
          val successes = results.collect { case value: Ok => value }
          pids.size() mustEqual 2
          successes.flatMap(_.delivery).size mustEqual 1
          if (scenario == "same command" || scenario == "resend retry") {
            successes.size mustEqual 2
            successes.head.dto mustEqual successes.last.dto
          } else {
            successes.size mustEqual 1
            results must contain(if (scenario == "two Owners") InvitationAlreadyExists else IdempotencyConflict)
          }
          val counts = await(db.run(for {
            invitations <- sql"SELECT count(*) FROM invitation WHERE organization_id=${org.id.toString}::uuid".as[Long].head
            commands    <- sql"SELECT count(*) FROM invitation_command WHERE organization_id=${org.id.toString}::uuid".as[Long].head
            audits <-
              sql"SELECT count(*) FROM invitation_audit WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${org.id.toString}::uuid)"
                .as[Long]
                .head
            deliveries <-
              sql"SELECT count(*) FROM invitation_delivery_attempt WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${org.id.toString}::uuid)"
                .as[Long]
                .head
            members <- sql"SELECT count(*) FROM organization_user WHERE organization_id=${org.id.toString}::uuid".as[Long].head
          } yield Vector(invitations, commands, audits, deliveries, members)))
          counts mustEqual Vector(if (isResend) initialCount.toLong else 1L, initialCount + 1L, initialCount + 1L, initialCount + 1L, 2L)
          if (isResend) {
            val generations =
              await(db.run(sql"SELECT generation FROM invitation WHERE organization_id=${org.id.toString}::uuid ORDER BY generation".as[Long]))
            generations mustEqual (if (initialCount == 2) Vector(1L, 2L) else Vector(2L))
          }
          ok
        } finally {
          await(
            db.run(
              DBIO
                .seq(
                  sqlu"DELETE FROM invitation_command WHERE organization_id=${org.id.toString}::uuid",
                  sqlu"DELETE FROM invitation_audit WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${org.id.toString}::uuid)",
                  sqlu"DELETE FROM invitation_delivery_attempt WHERE invitation_id IN (SELECT id FROM invitation WHERE organization_id=${org.id.toString}::uuid)",
                  sqlu"DELETE FROM invitation WHERE organization_id=${org.id.toString}::uuid",
                  sqlu"DELETE FROM organization_user WHERE organization_id=${org.id.toString}::uuid",
                  sqlu"DELETE FROM organization WHERE id=${org.id.toString}::uuid",
                  sqlu"""DELETE FROM "user" WHERE id IN (${first.id.toString}::uuid,${second.id.toString}::uuid)"""
                )
                .transactionally
            )
          )
          db.close()
        }
      }
  }
}
