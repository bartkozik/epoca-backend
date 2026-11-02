package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.repositories.InvitationJdbc._
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{EmailCanonicalization, PageSizeAndNumber}

import java.sql.ResultSet
import java.time.Instant

/** Prepared JDBC; the service owns transaction boundaries and recovers conflicts after rollback. */
final class InvitationOnboardingRepositoryImpl extends InvitationOnboardingRepository {
  private val canonicalValueSql     = EmailCanonicalization.sql("identity_value")
  private val canonicalParameterSql = EmailCanonicalization.sql("?")
  private def read(r: ResultSet): Record = Record(
    Invitation(
      InvitationId(uuid(r, "id")),
      UserIdentityValue(r.getString("identity_value")),
      IdentityType.withName(r.getString("identity_type")),
      r.getArray("roles").getArray.asInstanceOf[Array[AnyRef]].toVector.map(v => OrganizationUserRole.withName(v.toString)),
      time(r, "expire_at").map(InvitationExpireAt(_)),
      time(r, "accept_at").map(InvitationAcceptAt(_)),
      time(r, "reject_at").map(InvitationRejectAt(_)),
      OrganizationId(uuid(r, "organization_id")),
      InvitationCreatedAt(instant(r, "created_at")),
      UserId(uuid(r, "created_by")),
      InvitationModifiedAt(instant(r, "modified_at")),
      UserId(uuid(r, "modified_by")),
      time(r, "archived_at").map(InvitationArchivedAt(_))
    ),
    r.getInt("protocol_version"),
    Option(r.getString("first_name")),
    Option(r.getString("last_name")),
    r.getLong("revision"),
    r.getLong("generation"),
    Option(r.getBytes("token_hash")).map(Digest(_)),
    Option(uuid(r, "accepted_user_id")).map(UserId(_)),
    Option(r.getString("locale")),
    Option(r.getString("canonical_email"))
  )
  private val columns =
    "id,identity_value,identity_type,roles,expire_at,accept_at,reject_at,organization_id,created_at,created_by,modified_at,modified_by,archived_at,protocol_version,first_name,last_name,revision,generation,token_hash,accepted_user_id,locale"
  private def values(r: Record): Seq[Any] = {
    val i = r.invitation
    Seq(
      i.id,
      i.identityValue,
      i.identityType.toString,
      i.roles,
      i.expireAt,
      i.acceptAt,
      i.rejectAt,
      i.organizationId,
      i.createdAt,
      i.createdBy,
      i.modifiedAt,
      i.modifiedBy,
      i.archivedAt,
      r.protocolVersion,
      r.firstName,
      r.lastName,
      r.revision,
      r.generation,
      r.tokenHash,
      r.acceptedUserId,
      r.locale
    )
  }
  override def insert(record: Record): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(ctx.connection, s"INSERT INTO invitation ($columns) VALUES (${Vector.fill(21)("?").mkString(",")})") { p =>
      bind(p, values(record): _*); p.executeUpdate(); ()
    }
  }
  private def select(id: InvitationId, lock: Boolean): DBIO[Option[Record]] = SimpleDBIO { ctx =>
    val suffix = if (lock) " FOR UPDATE" else ""
    statement(ctx.connection, s"SELECT * FROM invitation WHERE id=?$suffix") { p => bind(p, id); rows(p)(read).headOption }
  }
  override def find(id: InvitationId): DBIO[Option[Record]]           = select(id, false)
  override def lockInvitation(id: InvitationId): DBIO[Option[Record]] = select(id, true)
  override def update(
    record: Record,
    expectedRevision: Long,
    expectedGeneration: Long
  ): DBIO[Boolean] = SimpleDBIO { ctx =>
    val i = record.invitation
    // Only a v1 -> v2 upgrade may canonicalize the stored address; recipients never change.
    statement(
      ctx.connection,
      s"UPDATE invitation SET identity_value=CASE WHEN protocol_version=1 AND ?=2 THEN $canonicalValueSql ELSE identity_value END,protocol_version=?,roles=?,expire_at=?,accept_at=?,reject_at=?,modified_at=?,modified_by=?,archived_at=?,first_name=?,last_name=?,revision=revision+1,generation=?,token_hash=?,accepted_user_id=?,locale=? WHERE id=? AND revision=? AND generation=? AND (?=protocol_version OR (protocol_version=1 AND ?=2)) AND identity_type=? AND ((identity_type='MailAddress' AND canonical_email=$canonicalParameterSql) OR (identity_type<>'MailAddress' AND identity_value=?)) AND ? >= generation"
    ) { p =>
      bind(
        p,
        record.protocolVersion,
        record.protocolVersion,
        i.roles,
        i.expireAt,
        i.acceptAt,
        i.rejectAt,
        i.modifiedAt,
        i.modifiedBy,
        i.archivedAt,
        record.firstName,
        record.lastName,
        record.generation,
        record.tokenHash,
        record.acceptedUserId,
        record.locale,
        i.id,
        expectedRevision,
        expectedGeneration,
        record.protocolVersion,
        record.protocolVersion,
        i.identityType.toString,
        i.identityValue,
        i.identityValue,
        record.generation
      )
      p.executeUpdate() == 1
    }
  }
  override def findByTokenHash(hash: Digest): DBIO[Option[Record]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT * FROM invitation WHERE token_hash=?") { p => bind(p, hash); rows(p)(read).headOption }
  }
  override def findPendingByEmail(organizationId: OrganizationId, canonicalEmail: String): DBIO[Option[Record]] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      s"SELECT * FROM invitation WHERE organization_id=? AND canonical_email=$canonicalParameterSql AND accept_at IS NULL AND reject_at IS NULL AND archived_at IS NULL"
    ) { p =>
      bind(p, organizationId, canonicalEmail); rows(p)(read).headOption
    }
  }
  override def findInOrganization(organizationId: OrganizationId, id: InvitationId): DBIO[Option[Record]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT * FROM invitation WHERE organization_id=? AND id=?") { p =>
      bind(p, organizationId, id); rows(p)(read).headOption
    }
  }
  override def page(
    organizationId: OrganizationId,
    pagination: PageSizeAndNumber,
    canonicalEmail: Option[String]
  ): DBIO[Vector[Record]] = SimpleDBIO { ctx =>
    require(pagination.size > 0 && pagination.number > 0, "Invitation page size and number must be positive")
    val email = if (canonicalEmail.isDefined) s" AND canonical_email=$canonicalParameterSql" else ""
    statement(ctx.connection, s"SELECT * FROM invitation WHERE organization_id=?$email ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?") { p =>
      bind(
        p,
        (Seq[Any](organizationId) ++ canonicalEmail.toSeq ++ Seq[Any](pagination.size, pagination.size.toLong * (pagination.number.toLong - 1))): _*
      )
      rows(p)(read)
    }
  }
  override def count(organizationId: OrganizationId, canonicalEmail: Option[String]): DBIO[Long] = SimpleDBIO { ctx =>
    val email = if (canonicalEmail.isDefined) s" AND canonical_email=$canonicalParameterSql" else ""
    statement(ctx.connection, s"SELECT count(*) FROM invitation WHERE organization_id=?$email") { p =>
      bind(p, (Seq[Any](organizationId) ++ canonicalEmail.toSeq): _*); rows(p)(_.getLong(1)).head
    }
  }
  override def insertAttempt(a: DeliveryAttempt): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "INSERT INTO invitation_delivery_attempt (id,invitation_id,generation,started_at,finished_at,result,safe_error_code) VALUES (?,?,?,?,?,?,?)"
    ) { p =>
      bind(p, a.id, a.invitationId, a.generation, a.startedAt, a.finishedAt, a.result.map(_.databaseValue), a.safeErrorCode); p.executeUpdate(); ()
    }
  }
  override def finishAttempt(
    id: InvitationDeliveryAttemptId,
    invitationId: InvitationId,
    generation: Long,
    finishedAt: Instant,
    result: DeliveryResult,
    safeErrorCode: Option[String]
  ): DBIO[Boolean] = SimpleDBIO { ctx =>
    // Lock the invitation before testing generation: concurrent resend cannot slip between check and finalization.
    val current = statement(ctx.connection, "SELECT generation FROM invitation WHERE id=? FOR UPDATE") { p =>
      bind(p, invitationId); rows(p)(_.getLong(1)).headOption
    }
    current.contains(generation) && statement(
      ctx.connection,
      "UPDATE invitation_delivery_attempt SET finished_at=?,result=?,safe_error_code=? WHERE id=? AND invitation_id=? AND generation=? AND finished_at IS NULL"
    ) { p =>
      bind(p, finishedAt, result.databaseValue, safeErrorCode, id, invitationId, generation); p.executeUpdate() == 1
    }
  }
  override def attempts(invitationId: InvitationId): DBIO[Vector[DeliveryAttempt]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT * FROM invitation_delivery_attempt WHERE invitation_id=? ORDER BY started_at DESC,id DESC") { p =>
      bind(p, invitationId);
      rows(p)(r =>
        DeliveryAttempt(
          InvitationDeliveryAttemptId(uuid(r, "id")),
          invitationId,
          r.getLong("generation"),
          instant(r, "started_at"),
          time(r, "finished_at"),
          Option(r.getString("result")).map(DeliveryResult.fromDatabase),
          Option(r.getString("safe_error_code"))
        )
      )
    }
  }
  override def appendAudit(a: Audit): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "INSERT INTO invitation_audit (id,invitation_id,actor_id,action,occurred_at,before_data,after_data) VALUES (?,?,?,?,?,?,?)"
    ) { p =>
      bind(p, a.id, a.invitationId, a.actorId, a.action, a.occurredAt, a.beforeData, a.afterData); p.executeUpdate(); ()
    }
  }
  override def audit(invitationId: InvitationId): DBIO[Vector[Audit]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT * FROM invitation_audit WHERE invitation_id=? ORDER BY occurred_at DESC,id DESC") { p =>
      bind(p, invitationId);
      rows(p)(r =>
        Audit(
          InvitationAuditId(uuid(r, "id")),
          invitationId,
          Option(uuid(r, "actor_id")).map(UserId(_)),
          r.getString("action"),
          instant(r, "occurred_at"),
          json(r, "before_data"),
          json(r, "after_data")
        )
      )
    }
  }
  private def keyValues(k: CommandKey): Seq[Any] = Seq(k.actorId, k.organizationId, k.operation, k.requestId)
  override def insertCommand(c: Command): DBIO[Boolean] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "INSERT INTO invitation_command (actor_id,organization_id,operation,request_id,payload_hash,invitation_id,result_json) VALUES (?,?,?,?,?,?,?) ON CONFLICT ON CONSTRAINT invitation_command_pkey DO NOTHING"
    ) { p =>
      bind(p, (keyValues(c.key) ++ Seq[Any](c.payloadHash, c.invitationId, c.result)): _*); p.executeUpdate() == 1
    }
  }
  override def findCommand(key: CommandKey): DBIO[Option[Command]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT * FROM invitation_command WHERE actor_id=? AND organization_id=? AND operation=? AND request_id=?") { p =>
      bind(p, keyValues(key): _*);
      rows(p)(r => Command(key, Digest(r.getBytes("payload_hash")), InvitationId(uuid(r, "invitation_id")), json(r, "result_json").get)).headOption
    }
  }
}
