package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.RefreshToken
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository.RefreshTokenFilters
import pl.iterators.epoca.auth.repositories.RefreshTokenRepositoryImpl
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginResolveService._
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Instant
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class RefreshTokenConcurrencySpec extends SpecificationLike with Generators {
  sequential
  private implicit val ec: ExecutionContext  = ExecutionContext.global
  private def await[A](future: Future[A]): A = Await.result(future, 30.seconds)

  "refresh rotation" should {
    "allow one concurrent exchange then revoke the family without allowing a losing exchange to recreate it" in {
      val db = Database.forURL(
        sys.env("TEST_DB_URL"),
        sys.env.getOrElse("TEST_DB_USER", "postgres"),
        sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
        driver = "org.postgresql.Driver",
        executor = AsyncExecutor("refresh-security-race", 4, 4, 20, maxConnections = 4)
      )
      val users    = new UserRepositoryImpl()
      val tokens   = new RefreshTokenRepositoryImpl()
      val user     = allGenerators[User].normal.generate.copy(accountType = UserAccountType.Human, archivedAt = None)
      val identity = allGenerators[UserIdentity].normal.generate.copy(userId = user.id, `type` = IdentityType.MailAddress)
      val token = allGenerators[RefreshToken].normal.generate.copy(
        userId = user.id,
        authedBy = Seq(identity.id),
        accountAuthentication = None,
        expiredAt = Some(RefreshTokenExpiredAt(Instant.now().plusSeconds(3600)))
      )
      val context = allGenerators[LoginHttpRequestContext].normal.generate.copy(deviceId = token.deviceId)
      val resolver = new LoginResolveService(
        new PostgresIOTransactor(db),
        Config(10.minutes, 30.days, RefreshTokenValidForStrategy.FromFirstUsage),
        new JwtService(JwtService.Config("local-refresh-concurrency-secret")),
        tokens,
        new RefreshTokenValueGeneratorService
      )
      await(db.run((users.insert(user) >> tokens.upsert(token)).transactionally))
      try {
        // Both requests carry the same trusted snapshot, as when both HTTP requests finish the initial lookup before rotation.
        val request  = RefreshTokenExchange(context, UserWithIdentities(user, Seq(identity)), Nil, token)
        val start    = new CountDownLatch(1)
        def exchange = (IO.blocking(start.await()) *> resolver.resolve(request)).attempt.unsafeToFuture()
        val first    = exchange
        val second   = exchange
        start.countDown()
        val outcomes    = await(Future.sequence(Seq(first, second)))
        val remaining   = await(db.run(tokens.find(RefreshTokenFilters(userId = Some(user.id), deviceId = Some(token.deviceId)))))
        val replay      = resolver.resolve(request).attempt.unsafeRunSync()
        val afterReplay = await(db.run(tokens.find(RefreshTokenFilters(userId = Some(user.id), deviceId = Some(token.deviceId)))))
        outcomes.count(_.isRight) shouldEqual 1
        outcomes.count(_.isLeft) shouldEqual 1
        remaining shouldEqual None
        replay.isLeft shouldEqual true
        afterReplay shouldEqual None
      } finally
        try
          await(
            db.run(
              (tokens
                .purge(user.id, token.deviceId) >> sqlu"""DELETE FROM "user" WHERE id = ${user.id.toString}::uuid""").map(_ => ()).transactionally
            )
          )
        finally db.close()
    }
  }
}
