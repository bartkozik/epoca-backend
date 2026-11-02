package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.repositories.fixtures.InvitationFixture
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.EmailCanonicalization
import slick.jdbc.SetParameter

import java.sql.{DriverManager, Timestamp}
import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

class InvitationRetentionRepositorySpec extends PgRepositorySpec with OrganizationFixture with InvitationFixture {
  private implicit val instantParameter: SetParameter[Instant] = SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))
  private val repository                                       = new InvitationRetentionRepository
  private val now                                              = Instant.parse("2035-01-01T12:00:00Z")

  "invitation retention" should {
    "bound expired rate bucket deletion and preserve live buckets" in withDatabase {
      for {
        _ <- sqlu"DELETE FROM invitation_rate_bucket"
        _ <-
          sqlu"INSERT INTO invitation_rate_bucket SELECT 'test',decode(lpad(to_hex(n),64,'0'),'hex'),${now.minusSeconds(60)},1,${now.minusSeconds(1)} FROM generate_series(1,3) n"
        _         <- sqlu"INSERT INTO invitation_rate_bucket VALUES ('live',decode(repeat('00',32),'hex'),$now,1,${now.plusSeconds(60)})"
        deleted   <- repository.deleteExpiredRateBuckets(now, 2)
        remaining <- sql"SELECT count(*) FROM invitation_rate_bucket".as[Int].head
        live      <- sql"SELECT count(*) FROM invitation_rate_bucket WHERE dimension='live'".as[Int].head
      } yield (deleted mustEqual 2) and (remaining mustEqual 2) and (live mustEqual 1)
    }

    "bound access deletion and preserve unexpired and completed-but-live contexts" in withDatabase {
      for {
        org        <- withOrganization()
        invitation <- withInvitation(org)
        _          <- DBIO.sequence((1 to 3).map(_ => insertAccess(invitation.id, now.minusSeconds(1))))
        live       <- insertAccess(invitation.id, now.plusSeconds(60))
        completed  <- insertAccess(invitation.id, now.plusSeconds(60))
        _          <- sqlu"UPDATE invitation_access SET completed_at=$now WHERE id=${completed.toString}::uuid"
        deleted    <- repository.deleteExpiredAccess(now, 2)
        remaining  <- sql"SELECT count(*) FROM invitation_access WHERE invitation_id=${invitation.id.toString}::uuid".as[Int].head
        liveCount  <- sql"SELECT count(*) FROM invitation_access WHERE id IN (${live.toString}::uuid,${completed.toString}::uuid)".as[Int].head
      } yield (deleted mustEqual 2) and (remaining mustEqual 3) and (liveCount mustEqual 2)
    }

    "remove circular references only after both request and proof expire" in withDatabase {
      for {
        org        <- withOrganization()
        invitation <- withInvitation(org)
        _ <-
          sqlu"INSERT INTO saml_config VALUES (${org.id.toString}::uuid,'cert','issuer','https://idp.example','en',interval '10 minutes',true,true,$now,1)"
        expired       <- insertAccess(invitation.id, now.minusSeconds(1))
        activeRequest <- insertAccess(invitation.id, now.minusSeconds(1))
        activeProof   <- insertAccess(invitation.id, now.minusSeconds(1))
        _             <- insertRequest(org.id.toString, expired, now.minusSeconds(601), None)
        _             <- insertRequest(org.id.toString, activeRequest, now.minusSeconds(30), None)
        _             <- insertRequest(org.id.toString, activeProof, now.minusSeconds(601), Some(now.minusSeconds(30)))
        deleted       <- repository.deleteExpiredAccess(now, 10)
        remaining     <- sql"SELECT count(*) FROM invitation_access WHERE invitation_id=${invitation.id.toString}::uuid".as[Int].head
        requests      <- sql"SELECT count(*) FROM saml_request WHERE organization_id=${org.id.toString}::uuid".as[Int].head
      } yield (deleted mustEqual 2) and (remaining mustEqual 2) and (requests mustEqual 2)
    }

    "drain a large SAML history in bounded batches before deleting its access context" in withDatabase {
      for {
        org        <- withOrganization()
        invitation <- withInvitation(org)
        _ <-
          sqlu"INSERT INTO saml_config VALUES (${org.id.toString}::uuid,'cert','issuer','https://idp.example','en',interval '10 minutes',true,true,$now,1)"
        access        <- insertAccess(invitation.id, now.minusSeconds(1))
        _             <- DBIO.sequence((1 to 3).map(_ => insertRequest(org.id.toString, access, now.minusSeconds(601), None)))
        first         <- repository.deleteExpiredAccess(now, 1)
        firstRequests <- sql"SELECT count(*) FROM saml_request WHERE invitation_access_id=${access.toString}::uuid".as[Int].head
        firstAccess   <- sql"SELECT count(*) FROM invitation_access WHERE id=${access.toString}::uuid".as[Int].head
        second        <- repository.deleteExpiredAccess(now, 1)
        third         <- repository.deleteExpiredAccess(now, 1)
        remaining     <- sql"SELECT count(*) FROM invitation_access WHERE id=${access.toString}::uuid".as[Int].head
      } yield (first mustEqual 1) and (firstRequests mustEqual 2) and (firstAccess mustEqual 1) and
        (second mustEqual 1) and (third mustEqual 2) and (remaining mustEqual 0)
    }

    "preserve a SAML request referenced by another live access" in withDatabase {
      for {
        org        <- withOrganization()
        invitation <- withInvitation(org)
        _ <-
          sqlu"INSERT INTO saml_config VALUES (${org.id.toString}::uuid,'cert','issuer','https://idp.example','en',interval '10 minutes',true,true,$now,1)"
        expired <- insertAccess(invitation.id, now.minusSeconds(1))
        live    <- insertAccess(invitation.id, now.plusSeconds(60))
        _       <- insertRequest(org.id.toString, expired, now.minusSeconds(601), None)
        _ <-
          sqlu"UPDATE invitation_access SET saml_request_id=(SELECT saml_request_id FROM invitation_access WHERE id=${expired.toString}::uuid) WHERE id=${live.toString}::uuid"
        deleted   <- repository.deleteExpiredAccess(now, 10)
        remaining <- sql"SELECT count(*) FROM saml_request WHERE invitation_access_id=${expired.toString}::uuid".as[Int].head
      } yield (deleted mustEqual 0) and (remaining mustEqual 1)
    }

    "skip locked accesses and requests instead of waiting in the opposite lock order" in {
      val (organizationId, invitationId, seeded) = Await.result(
        db.run((for {
          org        <- withOrganization()
          invitation <- withInvitation(org)
          _ <-
            sqlu"INSERT INTO saml_config VALUES (${org.id.toString}::uuid,'cert','issuer','https://idp.example','en',interval '10 minutes',true,true,$now,1)"
          access <- insertAccess(invitation.id, now.minusSeconds(1))
          _      <- insertRequest(org.id.toString, access, now.minusSeconds(601), None)
        } yield (org.id, invitation.id, access)).transactionally),
        5.seconds
      )
      try {
        val connection = DriverManager.getConnection(
          sys.env("TEST_DB_URL"),
          sys.env.getOrElse("TEST_DB_USER", "postgres"),
          sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
        )
        try {
          connection.setAutoCommit(false)
          Seq("invitation_access WHERE id", "saml_request WHERE invitation_access_id").foreach { relation =>
            val statement = connection.prepareStatement(s"SELECT 1 FROM $relation=? FOR UPDATE")
            try {
              statement.setObject(1, seeded)
              val rows = statement.executeQuery()
              try require(rows.next())
              finally rows.close()
              val deleted = Await.result(
                db.run((for {
                  _     <- sqlu"SET LOCAL lock_timeout = '250ms'"
                  count <- repository.deleteExpiredAccess(now, 10)
                } yield count).transactionally),
                5.seconds
              )
              require(deleted == 0, "Cleanup must leave locked access/request rows untouched")
            } finally {
              statement.close()
              connection.rollback()
            }
          }
        } finally connection.close()
        Await.result(db.run(repository.deleteExpiredAccess(now, 10)), 5.seconds) mustEqual 2
      } finally
        Await.result(
          db.run(
            DBIO
              .seq(
                sqlu"UPDATE invitation_access SET saml_request_id=NULL WHERE invitation_id=${invitationId.toString}::uuid",
                sqlu"DELETE FROM saml_request WHERE organization_id=${organizationId.toString}::uuid",
                sqlu"DELETE FROM invitation_access WHERE invitation_id=${invitationId.toString}::uuid",
                sqlu"DELETE FROM saml_config WHERE organization_id=${organizationId.toString}::uuid",
                sqlu"DELETE FROM invitation WHERE id=${invitationId.toString}::uuid",
                sqlu"DELETE FROM organization WHERE id=${organizationId.toString}::uuid"
              )
              .transactionally
          ),
          5.seconds
        )
    }

    "provide a usable canonical identity lookup index for the exact email expression" in withDatabase {
      val expression = EmailCanonicalization.sql("value")
      for {
        _ <- sqlu"SET LOCAL enable_seqscan = off"
        plan <-
          sql"EXPLAIN SELECT user_id FROM user_identity WHERE type IN ('MailAddress','Saml') AND #$expression = 'lookup@example.test'".as[String]
      } yield plan.mkString(" ") must contain("user_identity_canonical_email_idx")
    }
  }

  private def insertAccess(invitation: InvitationId, expires: Instant): DBIO[UUID] = {
    val id = UUID.randomUUID()
    for {
      _ <-
        sqlu"INSERT INTO invitation_access(id,cookie_hash,csrf_hash,invitation_id,generation,expires_at) VALUES (${id.toString}::uuid,decode(md5(${id.toString}) || md5(${id.toString}),'hex'),decode(repeat('00',32),'hex'),${invitation.toString}::uuid,1,$expires)"
    } yield id
  }

  private def insertRequest(
    organization: String,
    access: UUID,
    created: Instant,
    verified: Option[Instant]
  ): DBIO[Unit] = {
    val id = UUID.randomUUID().toString
    for {
      _ <-
        sqlu"INSERT INTO saml_request(id,used,created_at,organization_id,trust_epoch,purpose,browser_nonce_hash,invitation_access_id) VALUES ($id,false,$created,$organization::uuid,1,'Invitation',decode(repeat('00',32),'hex'),${access.toString}::uuid)"
      _ <-
        verified.fold[DBIO[Int]](DBIO.successful(0))(at =>
          sqlu"UPDATE saml_request SET verified_at=$at,verified_issuer='issuer',verified_subject='subject',verified_canonical_email='test@example.test' WHERE id=$id"
        )
      _ <- sqlu"UPDATE invitation_access SET saml_request_id=$id WHERE id=${access.toString}::uuid"
    } yield ()
  }
}
