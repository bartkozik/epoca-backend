package pl.iterators.epoca.oauth.repositories

import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.{
  OAuthClientFilters,
  OAuthClientMutationResult,
  OAuthClientSnapshot,
  OAuthPolicyReplaceResult
}
import pl.iterators.epoca.oauth.repositories.fixtures.ServiceAccountFixture
import pl.iterators.epoca.oauth.time.{OAuthAuthoritativeTime, PostgresOAuthAuthoritativeTime}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PageSizeAndNumber
import slick.jdbc.SetParameter
import slick.util.AsyncExecutor

import java.sql.{Timestamp, Types}
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Properties, UUID}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise}

class OAuthClientRepositoryImplSpec
    extends PgRepositorySpec
    with UserFixture
    with OrganizationFixture
    with ServiceAccountFixture
    with ClientFixture
    with ProjectFixture {
  private val authoritativeTime                              = new PostgresOAuthAuthoritativeTime()
  private val repository: OAuthClientRepository              = new OAuthClientRepositoryImpl(authoritativeTime)
  private val organizationGenerator: Generator[Organization] = allGenerators[Organization].normal
  private val clientGenerator: Generator[Client]             = allGenerators[Client].normal
  private val projectGenerator: Generator[Project]           = allGenerators[Project].normal
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  "client snapshots" should {
    "insert the client before its complete scope and project policy" in withDatabase {
      for {
        human          <- withUser()
        organization   <- fixtureOrganization(human)
        businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        project        <- withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        identity       <- withServiceAccountIdentity(organization)
        now = Instant.parse("2026-08-13T07:00:00Z")
        client = OAuthClient(
                   id = OAuthClientId(UUID.randomUUID()),
                   clientId = OAuthClientPublicId(s"epoca_test_${UUID.randomUUID()}"),
                   organizationId = organization.id,
                   serviceAccountUserId = identity.user.id,
                   name = OAuthClientName("Inserted by repository"),
                   status = OAuthClientStatus.Active,
                   projectAccessMode = OAuthProjectAccessMode.SelectedProjects,
                   secretHash = OAuthClientSecretHash("inserted-bcrypt-hash"),
                   secretVersion = OAuthClientSecretVersion(1L),
                   version = OAuthClientVersion(1L),
                   policyVersion = OAuthClientPolicyVersion(1L),
                   createdAt = OAuthClientCreatedAt(now),
                   createdBy = human.id,
                   modifiedAt = OAuthClientModifiedAt(now),
                   modifiedBy = human.id,
                   secretRotatedAt = OAuthClientSecretRotatedAt(now),
                   secretRotatedBy = human.id,
                   revokedAt = None,
                   revokedBy = None,
                   expiresAt = Some(OAuthClientExpiresAt(Instant.parse("2027-01-02T03:04:05.123456Z"))),
                   lastUsedAt = Some(OAuthClientLastUsedAt(Instant.parse("2026-08-13T08:09:10.654321Z")))
                 )
        expected =
          OAuthClientSnapshot(client, Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead), OAuthProjectAccess.SelectedProjects(Set(project.id)))
        inserted <- repository.insert(expected)
        _        <- sqlu"SET CONSTRAINTS ALL IMMEDIATE"
        found    <- repository.findPublic(client.clientId)
        locked   <- repository.lock(client.id)
      } yield {
        inserted shouldEqual expected
        found shouldEqual Some(expected)
        locked shouldEqual Some(expected)
      }
    }

    "load the public client, scopes, and project policy" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        snapshot     <- repository.findPublic(account.publicId)
      } yield {
        snapshot.map(_.client.id) shouldEqual Some(account.oauthClientId)
        snapshot.map(_.scopes) shouldEqual Some(Set(OAuthScope.UsersRead))
        snapshot.map(_.projectAccess) shouldEqual Some(OAuthProjectAccess.AllProjects)
        snapshot.flatMap(_.client.expiresAt) should beNone
        snapshot.flatMap(_.client.lastUsedAt) should beNone
      }
    }

    "round-trip administrative timestamps at PostgreSQL precision" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        createdAt  = Instant.parse("2020-01-01T00:00:00.123456Z")
        expiresAt  = OAuthClientExpiresAt(Instant.parse("2020-01-02T03:04:05.123456Z"))
        lastUsedAt = OAuthClientLastUsedAt(Instant.parse("2020-01-01T02:03:04.654321Z"))
        account  <- withLegalServiceAccount(organization, human, createdAt = createdAt, expiresAt = Some(expiresAt), lastUsedAt = Some(lastUsedAt))
        snapshot <- repository.findPublic(account.publicId)
      } yield {
        snapshot.map(_.client.createdAt) shouldEqual Some(OAuthClientCreatedAt(createdAt))
        snapshot.map(_.client.modifiedAt) shouldEqual Some(OAuthClientModifiedAt(createdAt))
        snapshot.map(_.client.secretRotatedAt) shouldEqual Some(OAuthClientSecretRotatedAt(createdAt))
        snapshot.flatMap(_.client.expiresAt) shouldEqual Some(expiresAt)
        snapshot.flatMap(_.client.lastUsedAt) shouldEqual Some(lastUsedAt)
      }
    }

    "read a public snapshot from one PostgreSQL statement while its policy is replaced" in {
      val (account, project) = Await.result(
        db.run((for {
          human          <- withUser()
          organization   <- fixtureOrganization(human)
          account        <- withLegalServiceAccount(organization, human)
          businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
          project        <- withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        } yield account -> project).transactionally),
        10.seconds
      )
      val readerDb = independentDatabase("oauth-public-snapshot-reader")
      val writerDb = independentDatabase("oauth-public-snapshot-writer")
      val gate     = new OneShotExecutionGate(ExecutionContext.global)

      try {
        val readerRepository = new OAuthClientRepositoryImpl(authoritativeTime)(gate)
        val writerRepository = new OAuthClientRepositoryImpl(authoritativeTime)
        gate.arm()
        val reading = readerDb.run(readerRepository.findPublic(account.publicId))
        Await.result(gate.paused, 10.seconds)

        val updated = Await.result(
          writerDb.run(
            writerRepository
              .replacePolicy(
                account.oauthClientId,
                Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead),
                OAuthProjectAccess.SelectedProjects(Set(project.id))
              )
              .transactionally
          ),
          10.seconds
        )
        gate.release()
        val observed = Await.result(reading, 10.seconds).get

        val originalState =
          (OAuthClientVersion(1L), OAuthClientPolicyVersion(1L), Set(OAuthScope.UsersRead), OAuthProjectAccess.AllProjects)
        val updatedState  = (updated.client.version, updated.client.policyVersion, updated.scopes, updated.projectAccess)
        val observedState = (observed.client.version, observed.client.policyVersion, observed.scopes, observed.projectAccess)

        Set(originalState, updatedState).contains(observedState) should beTrue
      } finally {
        gate.release()
        readerDb.close()
        writerDb.close()
      }
    }

    "read each listed page from one PostgreSQL statement while a policy is replaced" in {
      val (account, project, organization) = Await.result(
        db.run((for {
          human          <- withUser()
          organization   <- fixtureOrganization(human)
          account        <- withLegalServiceAccount(organization, human)
          businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
          project        <- withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        } yield (account, project, organization)).transactionally),
        10.seconds
      )
      val readerDb = independentDatabase("oauth-list-snapshot-reader")
      val writerDb = independentDatabase("oauth-list-snapshot-writer")
      val gate     = new OneShotExecutionGate(ExecutionContext.global)

      try {
        val readerRepository = new OAuthClientRepositoryImpl(authoritativeTime)(gate)
        val writerRepository = new OAuthClientRepositoryImpl(authoritativeTime)
        val at               = Instant.parse("2026-08-13T08:00:00Z")
        gate.arm()
        val reading = readerDb.run(readerRepository.list(OAuthClientFilters(organization.id), at))
        Await.result(gate.paused, 10.seconds)

        val updated = Await.result(
          writerDb.run(
            writerRepository
              .replacePolicy(
                account.oauthClientId,
                Set(OAuthScope.ClientsRead, OAuthScope.ProjectsRead),
                OAuthProjectAccess.SelectedProjects(Set(project.id))
              )
              .transactionally
          ),
          10.seconds
        )
        gate.release()
        val observed = Await.result(reading, 10.seconds).find(_.client.id == account.oauthClientId).get

        val originalState =
          (OAuthClientVersion(1L), OAuthClientPolicyVersion(1L), Set(OAuthScope.UsersRead), OAuthProjectAccess.AllProjects)
        val updatedState  = (updated.client.version, updated.client.policyVersion, updated.scopes, updated.projectAccess)
        val observedState = (observed.client.version, observed.client.policyVersion, observed.scopes, observed.projectAccess)

        Set(originalState, updatedState).contains(observedState) should beTrue
      } finally {
        gate.release()
        readerDb.close()
        writerDb.close()
      }
    }

    "replace the full policy atomically and keep semantic no-ops version-stable" in withDatabase {
      for {
        human          <- withUser()
        organization   <- fixtureOrganization(human)
        account        <- withLegalServiceAccount(organization, human)
        businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        project        <- withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        initial        <- repository.lock(account.oauthClientId).map(_.get)
        noOp           <- repository.replacePolicy(account.oauthClientId, initial.scopes, initial.projectAccess)
        changed <- repository.replacePolicy(
                     account.oauthClientId,
                     Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead),
                     OAuthProjectAccess.SelectedProjects(Set(project.id))
                   )
        current <- repository.lock(account.oauthClientId).map(_.get)
      } yield {
        noOp shouldEqual initial
        noOp.client.version shouldEqual initial.client.version
        noOp.client.policyVersion shouldEqual initial.client.policyVersion
        changed shouldEqual current
        current.scopes shouldEqual Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)
        current.projectAccess shouldEqual OAuthProjectAccess.SelectedProjects(Set(project.id))
        current.client.version.asInstanceOf[Long] shouldEqual initial.client.version.asInstanceOf[Long] + 1L
        current.client.policyVersion.asInstanceOf[Long] shouldEqual initial.client.policyVersion.asInstanceOf[Long] + 1L
      }
    }

    "use the injected authoritative time for legacy policy and secret mutations" in withDatabase {
      val fixedNow = Instant.parse("2034-05-06T07:08:09Z")
      val fixedTime = new OAuthAuthoritativeTime {
        override def now: DBIO[Instant] = DBIO.successful(fixedNow)
      }
      val fixedRepository = new OAuthClientRepositoryImpl(fixedTime)

      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        changed      <- fixedRepository.replacePolicy(account.oauthClientId, Set(OAuthScope.ProjectsRead), OAuthProjectAccess.AllProjects)
        rotated      <- fixedRepository.replaceSecret(account.oauthClientId, "oauth-injected-time-hash")
      } yield (changed.client.modifiedAt shouldEqual OAuthClientModifiedAt(fixedNow)) and
        (rotated.client.modifiedAt shouldEqual OAuthClientModifiedAt(fixedNow)) and
        (rotated.client.secretRotatedAt shouldEqual OAuthClientSecretRotatedAt(fixedNow))
    }

    "page clients in a stable newest-first order" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        first        <- withLegalServiceAccount(organization, human)
        second       <- withLegalServiceAccount(organization, human)
        third        <- withLegalServiceAccount(organization, human)
        timestamp = Instant.parse("2026-08-13T08:00:00Z")
        firstId   = first.oauthClientId.asInstanceOf[UUID]
        secondId  = second.oauthClientId.asInstanceOf[UUID]
        thirdId   = third.oauthClientId.asInstanceOf[UUID]
        _ <-
          sqlu"update oauth_client set created_at = $timestamp where id in ($firstId, $secondId, $thirdId)"
        firstPage <-
          repository.list(
            OAuthClientFilters(organizationId = organization.id, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))),
            timestamp
          )
        secondPage <-
          repository.list(
            OAuthClientFilters(organizationId = organization.id, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))),
            timestamp
          )
        count <- repository.count(OAuthClientFilters(organizationId = organization.id), timestamp)
      } yield {
        val expected = Seq(first.oauthClientId, second.oauthClientId, third.oauthClientId).sortBy(_.toString).reverse
        (firstPage ++ secondPage).map(_.client.id) shouldEqual expected
        firstPage.map(_.client.id).toSet.intersect(secondPage.map(_.client.id).toSet) should beEmpty
        count shouldEqual 3
      }
    }

    "filter and count mutually exclusive effective lifecycle states at one explicit instant" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        at = Instant.parse("2026-08-13T09:00:00.123456Z")
        active <-
          withLegalServiceAccount(organization, human, createdAt = at.minusSeconds(30L), expiresAt = Some(OAuthClientExpiresAt(at.plusSeconds(1L))))
        expired <- withLegalServiceAccount(organization, human, createdAt = at.minusSeconds(20L), expiresAt = Some(OAuthClientExpiresAt(at)))
        revokedFuture <- withLegalServiceAccount(
                           organization,
                           human,
                           createdAt = at.minusSeconds(10L),
                           expiresAt = Some(OAuthClientExpiresAt(at.plusSeconds(3600L)))
                         )
        revokedPast <- withLegalServiceAccount(
                         organization,
                         human,
                         createdAt = at.minusSeconds(7200L),
                         expiresAt = Some(OAuthClientExpiresAt(at.minusSeconds(3600L)))
                       )
        _ <- sqlu"""UPDATE oauth_client
                    SET status = 'Revoked', revoked_at = $at, revoked_by = ${human.id}
                    WHERE id IN (${revokedFuture.oauthClientId}, ${revokedPast.oauthClientId})"""
        activeRows   <- repository.list(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Active)), at)
        activeCount  <- repository.count(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Active)), at)
        expiredRows  <- repository.list(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Expired)), at)
        expiredCount <- repository.count(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Expired)), at)
        revokedRows  <- repository.list(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Revoked)), at)
        revokedCount <- repository.count(OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Revoked)), at)
      } yield {
        activeRows.map(_.client.id) shouldEqual Seq(active.oauthClientId)
        activeCount shouldEqual 1
        expiredRows.map(_.client.id) shouldEqual Seq(expired.oauthClientId)
        expiredCount shouldEqual 1
        revokedRows.map(_.client.id).toSet shouldEqual Set(revokedFuture.oauthClientId, revokedPast.oauthClientId)
        revokedCount shouldEqual 2
      }
    }

    "page all effective states stably with list and count agreement" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        at        = Instant.parse("2026-08-13T09:30:00Z")
        createdAt = Instant.parse("2026-08-13T09:00:00Z")
        active  <- withLegalServiceAccount(organization, human, createdAt = createdAt, expiresAt = Some(OAuthClientExpiresAt(at.plusSeconds(1L))))
        expired <- withLegalServiceAccount(organization, human, createdAt = createdAt, expiresAt = Some(OAuthClientExpiresAt(at)))
        revoked <- withLegalServiceAccount(organization, human, createdAt = createdAt, expiresAt = Some(OAuthClientExpiresAt(at.plusSeconds(1L))))
        _ <- sqlu"""UPDATE oauth_client
                    SET status = 'Revoked', revoked_at = $at, revoked_by = ${human.id}
                    WHERE id = ${revoked.oauthClientId}"""
        firstPage  <- repository.list(OAuthClientFilters(organization.id, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 1))), at)
        secondPage <- repository.list(OAuthClientFilters(organization.id, pageSizeAndNumber = Some(PageSizeAndNumber(size = 2, number = 2))), at)
        count      <- repository.count(OAuthClientFilters(organization.id), at)
      } yield {
        val rows     = firstPage ++ secondPage
        val expected = Seq(active.oauthClientId, expired.oauthClientId, revoked.oauthClientId).sortBy(_.toString).reverse
        rows.map(_.client.id) shouldEqual expected
        firstPage.map(_.client.id).toSet.intersect(secondPage.map(_.client.id).toSet) should beEmpty
        rows.map(snapshot => OAuthClientEffectiveStatus.resolve(snapshot.client.status, snapshot.client.expiresAt, at)).toSet shouldEqual
          Set(OAuthClientEffectiveStatus.Active, OAuthClientEffectiveStatus.Expired, OAuthClientEffectiveStatus.Revoked)
        count shouldEqual rows.size
      }
    }

    "use the caller-supplied instant consistently across an expiry boundary" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        expiry = Instant.parse("2026-08-13T10:00:00Z")
        account <- withLegalServiceAccount(organization, human, createdAt = expiry.minusSeconds(60L), expiresAt = Some(OAuthClientExpiresAt(expiry)))
        filters = OAuthClientFilters(organization.id, Some(OAuthClientEffectiveStatus.Active))
        beforeRows  <- repository.list(filters, expiry.minusNanos(1000L))
        beforeCount <- repository.count(filters, expiry.minusNanos(1000L))
        atRows      <- repository.list(filters, expiry)
        atCount     <- repository.count(filters, expiry)
      } yield {
        beforeRows.map(_.client.id) shouldEqual Seq(account.oauthClientId)
        beforeCount shouldEqual 1
        atRows should beEmpty
        atCount shouldEqual 0
      }
    }

    "serialize concurrent versioned policy replacements without partial child rows" in {
      val (account, policyProjects) = Await.result(
        db.run((for {
          human          <- withUser()
          organization   <- fixtureOrganization(human)
          account        <- withLegalServiceAccount(organization, human)
          businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
          policyProjects <- DBIO.sequence(
                              (1 to 4).map(_ =>
                                withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
                              )
                            )
        } yield account -> policyProjects).transactionally),
        10.seconds
      )
      val firstDb  = independentDatabase("oauth-policy-first")
      val secondDb = independentDatabase("oauth-policy-second")
      val readDb   = independentDatabase("oauth-policy-read")
      val readyOne = Promise[Unit]()
      val readyTwo = Promise[Unit]()
      val release  = Promise[Unit]()

      def attempt(
        repository: OAuthClientRepository,
        ready: Promise[Unit],
        replacement: Set[OAuthScope],
        access: OAuthProjectAccess
      ): DBIO[OAuthPolicyReplaceResult] =
        (for {
          _      <- DBIO.successful { ready.trySuccess(()); () }
          _      <- DBIO.from(release.future)
          result <- repository.replacePolicyAtVersion(account.oauthClientId, expectedVersion = 1L, replacement, access)
        } yield result).transactionally

      val firstScopes: Set[OAuthScope]     = Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)
      val secondScopes: Set[OAuthScope]    = Set(OAuthScope.UsersRead, OAuthScope.ClientsRead)
      val firstAccess: OAuthProjectAccess  = OAuthProjectAccess.SelectedProjects(policyProjects.take(2).map(_.id).toSet)
      val secondAccess: OAuthProjectAccess = OAuthProjectAccess.SelectedProjects(policyProjects.drop(2).map(_.id).toSet)
      val firstResult  = firstDb.run(attempt(new OAuthClientRepositoryImpl(authoritativeTime), readyOne, firstScopes, firstAccess))
      val secondResult = secondDb.run(attempt(new OAuthClientRepositoryImpl(authoritativeTime), readyTwo, secondScopes, secondAccess))

      try {
        Await.result(readyOne.future.zip(readyTwo.future), 10.seconds)
        release.success(())
        val results = Await.result(firstResult.zip(secondResult), 10.seconds)
        val current =
          Await.result(readDb.run(new OAuthClientRepositoryImpl(authoritativeTime).lock(account.oauthClientId).transactionally), 10.seconds).get

        Seq(results._1, results._2).count(_.isInstanceOf[OAuthPolicyReplaceResult.Applied]) shouldEqual 1
        Seq(results._1, results._2).count(_.isInstanceOf[OAuthPolicyReplaceResult.Stale]) shouldEqual 1
        current.client.version.asInstanceOf[Long] shouldEqual 2L
        current.client.policyVersion.asInstanceOf[Long] shouldEqual 2L
        Set(firstScopes -> firstAccess, secondScopes -> secondAccess).contains(current.scopes -> current.projectAccess) should beTrue
      } finally {
        release.trySuccess(())
        firstDb.close()
        secondDb.close()
        readDb.close()
      }
    }

    "replace a secret without invalidating policy and revoke terminally" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        initial      <- repository.lock(account.oauthClientId).map(_.get)
        rotated      <- repository.replaceSecret(account.oauthClientId, "replacement-bcrypt-hash")
        revokedAt = Instant.parse("2026-08-13T10:30:00Z")
        revoked      <- repository.revoke(account.oauthClientId, revokedAt, human.id)
        secondRevoke <- repository.revoke(account.oauthClientId, revokedAt.plusSeconds(1L), human.id).asTry
      } yield {
        rotated.client.secretHash shouldEqual OAuthClientSecretHash("replacement-bcrypt-hash")
        rotated.client.secretVersion.asInstanceOf[Long] shouldEqual initial.client.secretVersion.asInstanceOf[Long] + 1L
        rotated.client.version.asInstanceOf[Long] shouldEqual initial.client.version.asInstanceOf[Long] + 1L
        rotated.client.policyVersion shouldEqual initial.client.policyVersion
        revoked.client.status shouldEqual OAuthClientStatus.Revoked
        revoked.client.version.asInstanceOf[Long] shouldEqual rotated.client.version.asInstanceOf[Long] + 1L
        revoked.client.policyVersion.asInstanceOf[Long] shouldEqual rotated.client.policyVersion.asInstanceOf[Long] + 1L
        revoked.client.secretVersion shouldEqual rotated.client.secretVersion
        revoked.client.revokedAt shouldEqual Some(OAuthClientRevokedAt(revokedAt))
        revoked.client.revokedBy shouldEqual Some(human.id)
        secondRevoke.isFailure should beTrue
      }
    }

    "apply a versioned full update once and persist its human actor without partial policy rows" in withDatabase {
      for {
        human          <- withUser()
        organization   <- fixtureOrganization(human)
        account        <- withLegalServiceAccount(organization, human)
        businessClient <- withClient(organization, clientGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        project        <- withProject(organization, businessClient, projectGenerator.generate.copy(createdBy = human.id, modifiedBy = human.id))
        changedAt     = Instant.parse("2026-08-13T11:00:00Z")
        changedExpiry = Some(OAuthClientExpiresAt(Instant.parse("2027-08-13T11:00:00.123456Z")))
        changed <- repository.updateAtVersion(
                     account.oauthClientId,
                     expectedVersion = 1L,
                     OAuthClientName("Updated integration"),
                     Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead),
                     OAuthProjectAccess.SelectedProjects(Set(project.id)),
                     changedExpiry,
                     changedAt,
                     human.id
                   )
        noOp <- repository.updateAtVersion(
                  account.oauthClientId,
                  expectedVersion = 2L,
                  OAuthClientName("Updated integration"),
                  Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead),
                  OAuthProjectAccess.SelectedProjects(Set(project.id)),
                  changedExpiry,
                  changedAt.plusSeconds(1L),
                  human.id
                )
        stale <- repository.updateAtVersion(
                   account.oauthClientId,
                   expectedVersion = 1L,
                   OAuthClientName("Must not persist"),
                   Set(OAuthScope.UsersRead),
                   OAuthProjectAccess.AllProjects,
                   expiresAt = None,
                   changedAt.plusSeconds(1L),
                   human.id
                 )
        current <- repository.lock(account.oauthClientId).map(_.get)
      } yield {
        changed shouldEqual OAuthClientMutationResult.Applied(current)
        noOp shouldEqual OAuthClientMutationResult.Applied(current)
        stale shouldEqual OAuthClientMutationResult.Stale(currentVersion = 2L)
        current.client.name shouldEqual OAuthClientName("Updated integration")
        current.client.modifiedAt shouldEqual OAuthClientModifiedAt(changedAt)
        current.client.modifiedBy shouldEqual human.id
        current.client.status shouldEqual OAuthClientStatus.Active
        current.client.expiresAt shouldEqual changedExpiry
        current.client.version shouldEqual OAuthClientVersion(2L)
        current.client.policyVersion shouldEqual OAuthClientPolicyVersion(2L)
        current.scopes shouldEqual Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)
        current.projectAccess shouldEqual OAuthProjectAccess.SelectedProjects(Set(project.id))
      }
    }

    "touch last usage conditionally without changing managed or audited state" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        expiresAt = Some(OAuthClientExpiresAt(Instant.parse("2027-08-13T12:00:00.123456Z")))
        account     <- withLegalServiceAccount(organization, human, createdAt = Instant.parse("2026-08-13T11:00:00.123456Z"), expiresAt = expiresAt)
        before      <- repository.lock(account.oauthClientId).map(_.get)
        auditBefore <- sql"SELECT count(*) FROM oauth_audit_event WHERE oauth_client_id = ${account.oauthClientId}".as[Int].head
        firstAt = Instant.parse("2026-08-13T12:00:00.123456Z")
        first       <- repository.touchLastUsedAt(account.oauthClientId, firstAt, 5.minutes)
        inside      <- repository.touchLastUsedAt(account.oauthClientId, firstAt.plusSeconds(299L), 5.minutes)
        afterInside <- repository.lock(account.oauthClientId).map(_.get)
        boundaryAt = firstAt.plusSeconds(300L)
        boundary      <- repository.touchLastUsedAt(account.oauthClientId, boundaryAt, 5.minutes)
        afterBoundary <- repository.lock(account.oauthClientId).map(_.get)
        laterAt = boundaryAt.plusSeconds(301L)
        later      <- repository.touchLastUsedAt(account.oauthClientId, laterAt, 5.minutes)
        unknown    <- repository.touchLastUsedAt(OAuthClientId(UUID.randomUUID()), laterAt, 5.minutes)
        after      <- repository.lock(account.oauthClientId).map(_.get)
        auditAfter <- sql"SELECT count(*) FROM oauth_audit_event WHERE oauth_client_id = ${account.oauthClientId}".as[Int].head
      } yield {
        first should beTrue
        inside should beFalse
        afterInside.client.lastUsedAt shouldEqual Some(OAuthClientLastUsedAt(firstAt))
        boundary should beTrue
        afterBoundary.client.lastUsedAt shouldEqual Some(OAuthClientLastUsedAt(boundaryAt))
        later should beTrue
        unknown should beFalse
        after.client.lastUsedAt shouldEqual Some(OAuthClientLastUsedAt(laterAt))
        after.client.copy(lastUsedAt = before.client.lastUsedAt) shouldEqual before.client
        after.scopes shouldEqual before.scopes
        after.projectAccess shouldEqual before.projectAccess
        after.client.expiresAt shouldEqual expiresAt
        auditAfter shouldEqual auditBefore
      }
    }

    "reject a negative usage interval without changing persisted or audited state" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        lastUsedAt = OAuthClientLastUsedAt(Instant.parse("2026-08-13T12:00:00.123456Z"))
        account <- withLegalServiceAccount(
                     organization,
                     human,
                     createdAt = Instant.parse("2026-08-13T11:00:00.123456Z"),
                     expiresAt = Some(OAuthClientExpiresAt(Instant.parse("2027-08-13T12:00:00.123456Z"))),
                     lastUsedAt = Some(lastUsedAt)
                   )
        before      <- repository.lock(account.oauthClientId).map(_.get)
        auditBefore <- sql"SELECT count(*) FROM oauth_audit_event WHERE oauth_client_id = ${account.oauthClientId}".as[Int].head
        attempted <- repository
                       .touchLastUsedAt(account.oauthClientId, Instant.parse("2026-08-13T13:00:00.123456Z"), -1.second)
                       .asTry
        after      <- repository.lock(account.oauthClientId).map(_.get)
        auditAfter <- sql"SELECT count(*) FROM oauth_audit_event WHERE oauth_client_id = ${account.oauthClientId}".as[Int].head
      } yield {
        attempted.isFailure should beTrue
        attempted.failed.toOption.map(_.getMessage) shouldEqual Some("OAuth client last-used minimum interval must not be negative")
        after shouldEqual before
        auditAfter shouldEqual auditBefore
      }
    }

    "allow a zero usage interval at the inclusive boundary" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        at = Instant.parse("2026-08-13T13:30:00.654321Z")
        account <- withLegalServiceAccount(organization, human, createdAt = at.minusSeconds(3600L), lastUsedAt = Some(OAuthClientLastUsedAt(at)))
        touched <- repository.touchLastUsedAt(account.oauthClientId, at, Duration.Zero)
        after   <- repository.lock(account.oauthClientId).map(_.get)
      } yield {
        touched should beTrue
        after.client.lastUsedAt shouldEqual Some(OAuthClientLastUsedAt(at))
      }
    }

    "allow exactly one concurrent touch in one usage window" in {
      val (account, now) = Await.result(
        db.run((for {
          human        <- withUser()
          organization <- fixtureOrganization(human)
          account      <- withLegalServiceAccount(organization, human, createdAt = Instant.parse("2026-08-13T12:30:00Z"))
        } yield account -> Instant.parse("2026-08-13T13:00:00.654321Z")).transactionally),
        10.seconds
      )
      val firstDb  = independentDatabase("oauth-touch-first")
      val secondDb = independentDatabase("oauth-touch-second")
      val readDb   = independentDatabase("oauth-touch-read")

      try {
        val first =
          firstDb.run(new OAuthClientRepositoryImpl(authoritativeTime).touchLastUsedAt(account.oauthClientId, now, 5.minutes).transactionally)
        val second =
          secondDb.run(new OAuthClientRepositoryImpl(authoritativeTime).touchLastUsedAt(account.oauthClientId, now, 5.minutes).transactionally)
        val results = Await.result(first.zip(second), 10.seconds)
        val current =
          Await.result(readDb.run(new OAuthClientRepositoryImpl(authoritativeTime).lock(account.oauthClientId).transactionally), 10.seconds).get

        Seq(results._1, results._2).count(identity) shouldEqual 1
        current.client.lastUsedAt shouldEqual Some(OAuthClientLastUsedAt(now))
      } finally {
        firstDb.close()
        secondDb.close()
        readDb.close()
      }
    }

    "rotate at an expected version with exact actor fields and reject a stale retry" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        rotatedAt = Instant.parse("2026-08-13T11:30:00Z")
        rotated <-
          repository.rotateSecretAtVersion(account.oauthClientId, expectedVersion = 1L, "versioned-replacement-bcrypt-hash", rotatedAt, human.id)
        stale <-
          repository.rotateSecretAtVersion(account.oauthClientId, expectedVersion = 1L, "must-not-persist", rotatedAt.plusSeconds(1L), human.id)
        current <- repository.lock(account.oauthClientId).map(_.get)
      } yield {
        rotated shouldEqual OAuthClientMutationResult.Applied(current)
        stale shouldEqual OAuthClientMutationResult.Stale(currentVersion = 2L)
        current.client.secretHash shouldEqual OAuthClientSecretHash("versioned-replacement-bcrypt-hash")
        current.client.secretVersion shouldEqual OAuthClientSecretVersion(2L)
        current.client.version shouldEqual OAuthClientVersion(2L)
        current.client.policyVersion shouldEqual OAuthClientPolicyVersion(1L)
        current.client.modifiedAt shouldEqual OAuthClientModifiedAt(rotatedAt)
        current.client.modifiedBy shouldEqual human.id
        current.client.secretRotatedAt shouldEqual OAuthClientSecretRotatedAt(rotatedAt)
        current.client.secretRotatedBy shouldEqual human.id
      }
    }

    "reject policy, rotation, and a second revoke before updating a terminal client" in withDatabase {
      for {
        human        <- withUser()
        organization <- fixtureOrganization(human)
        account      <- withLegalServiceAccount(organization, human)
        revokedAt = Instant.parse("2026-08-13T12:00:00Z")
        revoked <- repository.revokeAtVersion(account.oauthClientId, expectedVersion = 1L, revokedAt, human.id)
        policyAttempt <- repository
                           .replacePolicy(account.oauthClientId, Set(OAuthScope.UsersRead), OAuthProjectAccess.AllProjects)
                           .asTry
        rotateAttempt <-
          repository
            .rotateSecretAtVersion(account.oauthClientId, expectedVersion = 2L, "must-not-persist", revokedAt.plusSeconds(1L), human.id)
            .asTry
        secondRevoke <- repository.revoke(account.oauthClientId, revokedAt.plusSeconds(1L), human.id).asTry
      } yield {
        revoked must beAnInstanceOf[OAuthClientMutationResult.Applied]
        policyAttempt.failed.get must beAnInstanceOf[OAuthClientRepository.OAuthClientRevoked]
        rotateAttempt.failed.get must beAnInstanceOf[OAuthClientRepository.OAuthClientRevoked]
        secondRevoke.failed.get must beAnInstanceOf[OAuthClientRepository.OAuthClientRevoked]
      }
    }
  }

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

  private final class OneShotExecutionGate(delegate: ExecutionContext) extends ExecutionContext {
    private val armedOnce = new AtomicBoolean(false)
    private val waiting   = Promise[Unit]()
    private val continue  = Promise[Unit]()

    def arm(): Unit = armedOnce.set(true)
    def paused      = waiting.future
    def release(): Unit = {
      val _ = continue.trySuccess(())
      ()
    }
    override def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)

    override def execute(runnable: Runnable): Unit =
      if (armedOnce.compareAndSet(true, false)) {
        val _ = waiting.trySuccess(())
        continue.future.foreach(_ => delegate.execute(runnable))(delegate)
      } else delegate.execute(runnable)
  }
}
