package pl.iterators.epoca.oauth.repositories

import io.circe.Json
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthAuditRepository.{NewOAuthAuditEvent, OAuthAuditEventFilters}
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.util.AsyncExecutor

import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Properties, UUID}
import scala.concurrent.Await
import scala.concurrent.duration._

class OAuthAuditRepositoryImplSpec extends PgRepositorySpec with UserFixture with OrganizationFixture with ServiceAccountFixture {
  sequential

  private val repository: OAuthAuditRepository               = new OAuthAuditRepositoryImpl()
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal

  "audit persistence" should {
    "roll back a required event when its caller-owned transaction fails" in {
      val eventId = OAuthAuditEventId(UUID.randomUUID())
      val writer  = independentDatabase("oauth-audit-rollback-writer")
      val reader  = independentDatabase("oauth-audit-rollback-reader")
      val event   = unknownClientEvent(eventId, Instant.now().minusSeconds(60L))

      try {
        val failed = writer.run((repository.insert(event) >> DBIO.failed(new IllegalStateException("forced audit rollback"))).transactionally.asTry)
        Await.result(failed, 10.seconds).isFailure should beTrue
        Await.result(reader.run(repository.find(eventId)), 10.seconds) should beNone
      } finally {
        writer.close()
        reader.close()
      }
    }

    "store unknown-client authentication failures without the supplied raw identifier" in withDatabase {
      val rawIdentifier = "epoca_unknown_sensitive_identifier"
      val event         = unknownClientEvent(OAuthAuditEventId(UUID.randomUUID()), Instant.now().minusSeconds(60L).truncatedTo(ChronoUnit.MICROS))

      for {
        stored <- repository.insertUnknownClientFailure(
                    event.id,
                    event.occurredAt,
                    event.correlationId,
                    event.sourceIp,
                    OAuthAuditFailureCategory.UnknownClient
                  )
        found <- repository.find(event.id)
      } yield {
        stored.oauthClientId should beNone
        stored.organizationId should beNone
        stored.metadata.noSpaces must not contain rawIdentifier
        found shouldEqual Some(stored)
      }
    }

    "page client events newest first with an id tie-breaker" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        occurredAt = Instant.parse("2026-05-01T00:00:00Z")
        ids        = Seq.fill(3)(OAuthAuditEventId(UUID.randomUUID())).sortBy(_.toString).reverse
        _ <- DBIO.sequence(ids.map(id => repository.insert(tokenIssuedEvent(id, occurredAt, organization, account.oauthClientId, account.user))))
        first <- repository.list(
                   OAuthAuditEventFilters(oauthClientId = account.oauthClientId, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1)))
                 )
        second <- repository.list(
                    OAuthAuditEventFilters(oauthClientId = account.oauthClientId, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2)))
                  )
        count <- repository.count(OAuthAuditEventFilters(oauthClientId = account.oauthClientId))
      } yield {
        (first ++ second).map(_.id) shouldEqual ids
        count shouldEqual 3
      }
    }

    "delete only expired Authentication events in bounded oldest-first batches" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        cutoff       = Instant.now().minusSeconds(90L * 24L * 60L * 60L)
        oldOneId     = OAuthAuditEventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        oldTwoId     = OAuthAuditEventId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        oldThreeId   = OAuthAuditEventId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
        recentId     = OAuthAuditEventId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
        managementId = OAuthAuditEventId(UUID.fromString("00000000-0000-0000-0000-000000000005"))
        _             <- repository.insert(unknownClientEvent(oldTwoId, cutoff.minusSeconds(7200L)))
        _             <- repository.insert(unknownClientEvent(oldOneId, cutoff.minusSeconds(7200L)))
        _             <- repository.insert(unknownClientEvent(oldThreeId, cutoff.minusSeconds(3600L)))
        _             <- repository.insert(unknownClientEvent(recentId, cutoff))
        _             <- repository.insert(createEvent(managementId, cutoff.minusSeconds(7200L), organization, account.oauthClientId, human))
        firstDeleted  <- repository.deleteExpiredAuthentication(cutoff, batchSize = 1)
        oldOne        <- repository.find(oldOneId)
        oldTwo        <- repository.find(oldTwoId)
        oldThree      <- repository.find(oldThreeId)
        recent        <- repository.find(recentId)
        management    <- repository.find(managementId)
        secondDeleted <- repository.deleteExpiredAuthentication(cutoff, batchSize = 1)
        oldTwoAfter   <- repository.find(oldTwoId)
        thirdDeleted  <- repository.deleteExpiredAuthentication(cutoff, batchSize = 1)
        oldThreeAfter <- repository.find(oldThreeId)
      } yield {
        firstDeleted shouldEqual 1
        oldOne should beNone
        oldTwo should beSome
        oldThree should beSome
        recent should beSome
        management should beSome
        secondDeleted shouldEqual 1
        oldTwoAfter should beNone
        thirdDeleted shouldEqual 1
        oldThreeAfter should beNone
      }
    }
  }

  private def unknownClientEvent(id: OAuthAuditEventId, occurredAt: Instant): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      id = id,
      occurredAt = OAuthAuditOccurredAt(occurredAt),
      category = OAuthAuditCategory.Authentication,
      organizationId = None,
      oauthClientId = None,
      actorType = OAuthAuditActorType.Anonymous,
      actorUserId = None,
      eventType = OAuthAuditEventType.InvalidClient,
      outcome = OAuthAuditOutcome.Failure,
      correlationId = Some(OAuthAuditCorrelationId(s"correlation-${UUID.randomUUID()}")),
      tokenJti = None,
      sourceIp = Some(InetAddress.getByName("192.0.2.1")),
      metadata = Json.obj("failureCategory" -> Json.fromString("unknown-client"))
    )

  private def tokenIssuedEvent(
    id: OAuthAuditEventId,
    occurredAt: Instant,
    organization: Organization,
    oauthClientId: OAuthClientId,
    actor: User
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      id,
      OAuthAuditOccurredAt(occurredAt),
      OAuthAuditCategory.Authentication,
      Some(organization.id),
      Some(oauthClientId),
      OAuthAuditActorType.ServiceAccount,
      Some(actor.id),
      OAuthAuditEventType.TokenIssued,
      OAuthAuditOutcome.Success,
      None,
      Some(UUID.randomUUID()),
      None,
      Json.obj(
        "scopes"        -> Json.arr(Json.fromString("users:read")),
        "projectMode"   -> Json.fromString("AllProjects"),
        "projectIds"    -> Json.arr(),
        "policyVersion" -> Json.fromLong(1L),
        "secretVersion" -> Json.fromLong(1L)
      )
    )

  private def createEvent(
    id: OAuthAuditEventId,
    occurredAt: Instant,
    organization: Organization,
    oauthClientId: OAuthClientId,
    actor: User
  ): NewOAuthAuditEvent =
    NewOAuthAuditEvent(
      id,
      OAuthAuditOccurredAt(occurredAt),
      OAuthAuditCategory.Management,
      Some(organization.id),
      Some(oauthClientId),
      OAuthAuditActorType.Human,
      Some(actor.id),
      OAuthAuditEventType.Create,
      OAuthAuditOutcome.Success,
      None,
      None,
      None,
      Json.obj(
        "scopes"        -> Json.arr(Json.fromString("users:read")),
        "projectMode"   -> Json.fromString("AllProjects"),
        "projectIds"    -> Json.arr(),
        "version"       -> Json.fromLong(1L),
        "policyVersion" -> Json.fromLong(1L),
        "secretVersion" -> Json.fromLong(1L)
      )
    )

  private def fixtureOrganization(human: User): DBIO[Organization] =
    withOrganization(organizationGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))

  private def independentDatabase(name: String): Database =
    Database.forURL(
      url = sys.env.getOrElse("TEST_DB_URL", "epoca"),
      user = sys.env.getOrElse("TEST_DB_USER", "postgres"),
      password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"),
      driver = "org.postgresql.Driver",
      executor = AsyncExecutor(s"$name-${UUID.randomUUID()}", minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
      prop = new Properties()
    )
}
