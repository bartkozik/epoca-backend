package pl.iterators.epoca.invitation.repositories

import io.circe.Json
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.InvitationJdbc._
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

final class InvitationAccessRepositoryImpl extends InvitationAccessRepository {
  private def encode(proof: Proof): Json = Json.obj(
    "emailVerifiedAt" -> proof.emailVerifiedAt.fold(Json.Null)(t => Json.fromString(t.toString)),
    "saml" -> proof.saml.fold(Json.Null)(s =>
      Json.obj(
        "organizationId" -> Json.fromString(s.organizationId.toString),
        "trustEpoch"     -> Json.fromLong(s.trustEpoch),
        "issuer"         -> Json.fromString(s.issuer),
        "subject"        -> Json.fromString(s.subject),
        "canonicalEmail" -> Json.fromString(s.canonicalEmail),
        "verifiedAt"     -> Json.fromString(s.verifiedAt.toString)
      )
    )
  )
  private def decode(value: Json): Proof = {
    val c     = value.hcursor
    val email = c.get[Option[String]]("emailVerifiedAt").fold(throw _, identity).map(Instant.parse)
    val saml = c.get[Option[Json]]("saml").fold(throw _, identity).map { j =>
      val s                          = j.hcursor
      def text(name: String): String = s.get[String](name).fold(throw _, identity)
      VerifiedSamlProof(
        OrganizationId(UUID.fromString(text("organizationId"))),
        s.get[Long]("trustEpoch").fold(throw _, identity),
        text("issuer"),
        text("subject"),
        text("canonicalEmail"),
        Instant.parse(text("verifiedAt"))
      )
    }
    Proof(email, saml)
  }
  private def read(r: ResultSet): Access = Access(
    InvitationAccessId(uuid(r, "id")),
    Digest(r.getBytes("cookie_hash")),
    Digest(r.getBytes("csrf_hash")),
    InvitationId(uuid(r, "invitation_id")),
    r.getLong("generation"),
    decode(json(r, "proof").get),
    Option(uuid(r, "verified_user_id")).map(UserId(_)),
    Option(r.getString("saml_request_id")),
    instant(r, "expires_at"),
    time(r, "completed_at")
  )
  override def insert(a: Access): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "INSERT INTO invitation_access (id,cookie_hash,csrf_hash,invitation_id,generation,proof,verified_user_id,saml_request_id,expires_at,completed_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
    ) { p =>
      bind(
        p,
        a.id,
        a.cookieHash,
        a.csrfHash,
        a.invitationId,
        a.generation,
        encode(a.proof),
        a.verifiedUserId,
        a.samlRequestId,
        a.expiresAt,
        a.completedAt
      ); p.executeUpdate(); ()
    }
  }
  private def select(hash: Digest, lock: Boolean): DBIO[Option[Access]] = SimpleDBIO { ctx =>
    val suffix = if (lock) " FOR UPDATE" else ""
    statement(ctx.connection, s"SELECT * FROM invitation_access WHERE cookie_hash=?$suffix") { p => bind(p, hash); rows(p)(read).headOption }
  }
  private def selectId(id: InvitationAccessId, lock: Boolean): DBIO[Option[Access]] = SimpleDBIO { ctx =>
    val suffix = if (lock) " FOR UPDATE" else ""
    statement(ctx.connection, s"SELECT * FROM invitation_access WHERE id=?$suffix") { p => bind(p, id); rows(p)(read).headOption }
  }
  override def findById(id: InvitationAccessId): DBIO[Option[Access]] = selectId(id, false)
  override def lockById(id: InvitationAccessId): DBIO[Option[Access]] = selectId(id, true)
  override def rotateCookie(
    access: Access,
    cookieHash: Digest,
    csrfHash: Digest,
    now: Instant
  ): DBIO[Boolean] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "UPDATE invitation_access SET cookie_hash=?,csrf_hash=? WHERE id=? AND cookie_hash=? AND invitation_id=? AND generation=? AND completed_at IS NULL AND expires_at>?"
    ) { p =>
      bind(p, cookieHash, csrfHash, access.id, access.cookieHash, access.invitationId, access.generation, now); p.executeUpdate() == 1
    }
  }
  override def findByCookieHash(hash: Digest): DBIO[Option[Access]] = select(hash, false)
  override def lockByCookieHash(hash: Digest): DBIO[Option[Access]] = select(hash, true)
  override def update(a: Access, expectedGeneration: Long): DBIO[Boolean] = SimpleDBIO { ctx =>
    a.generation == expectedGeneration && statement(
      ctx.connection,
      "UPDATE invitation_access SET csrf_hash=?,proof=?,verified_user_id=?,saml_request_id=?,expires_at=?,completed_at=? WHERE id=? AND invitation_id=? AND generation=? AND completed_at IS NULL"
    ) { p =>
      bind(p, a.csrfHash, encode(a.proof), a.verifiedUserId, a.samlRequestId, a.expiresAt, a.completedAt, a.id, a.invitationId, expectedGeneration);
      p.executeUpdate() == 1
    }
  }
  override def rotateCsrf(id: InvitationAccessId, hash: Digest): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(ctx.connection, "UPDATE invitation_access SET csrf_hash=? WHERE id=?") { p => bind(p, hash, id); p.executeUpdate(); () }
  }
  override def revoke(id: InvitationAccessId, now: Instant): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(ctx.connection, "UPDATE invitation_access SET expires_at=LEAST(expires_at,?) WHERE id=?") { p =>
      bind(p, now, id); p.executeUpdate(); ()
    }
  }
  override def consumeRateBucket(
    dimension: String,
    keyHash: Digest,
    windowStart: Instant,
    expiresAt: Instant,
    limit: Long
  ): DBIO[Boolean] = SimpleDBIO { ctx =>
    require(limit > 0, "Rate limit must be positive")
    statement(
      ctx.connection,
      "INSERT INTO invitation_rate_bucket (dimension,key_hash,window_start,count,expires_at) VALUES (?,?,?,1,?) ON CONFLICT ON CONSTRAINT invitation_rate_bucket_pkey DO UPDATE SET count=invitation_rate_bucket.count+1 WHERE invitation_rate_bucket.count < ? RETURNING count"
    ) { p =>
      bind(p, dimension, keyHash, windowStart, expiresAt, limit); rows(p)(_.getLong(1)).nonEmpty
    }
  }
}
