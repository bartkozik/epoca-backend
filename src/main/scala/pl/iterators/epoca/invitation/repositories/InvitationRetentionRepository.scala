package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.repositories.InvitationJdbc._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant

class InvitationRetentionRepository {
  def deleteExpiredRateBuckets(now: Instant, batchSize: Int): DBIO[Int] = SimpleDBIO { ctx =>
    require(batchSize > 0, "Cleanup batch size must be positive")
    statement(
      ctx.connection,
      """WITH expired AS (
        | SELECT dimension,key_hash,window_start FROM invitation_rate_bucket WHERE expires_at <= ?
        | ORDER BY expires_at,dimension,key_hash,window_start LIMIT ? FOR UPDATE SKIP LOCKED
        |) DELETE FROM invitation_rate_bucket b USING expired e
        | WHERE b.dimension=e.dimension AND b.key_hash=e.key_hash AND b.window_start=e.window_start""".stripMargin
    ) { p => bind(p, now, batchSize); p.executeUpdate() }
  }.transactionally

  def deleteExpiredAccess(now: Instant, batchSize: Int): DBIO[Int] = SimpleDBIO { ctx =>
    require(batchSize > 0, "Cleanup batch size must be positive")
    val connection = ctx.connection
    val candidates = statement(
      connection,
      """SELECT a.id FROM invitation_access a WHERE a.expires_at <= ?
        | AND NOT EXISTS (
        |   SELECT 1 FROM saml_request r LEFT JOIN saml_config c ON c.organization_id=r.organization_id
        |   WHERE (r.invitation_access_id=a.id OR r.id=a.saml_request_id)
        |     AND (c.organization_id IS NULL OR r.created_at+c.validity_duration > ? OR r.verified_at > ?)
        | )
        | AND NOT EXISTS (
        |   SELECT 1 FROM saml_request r JOIN invitation_access other ON other.saml_request_id=r.id
        |   WHERE r.invitation_access_id=a.id AND other.id<>a.id
        | )
        | ORDER BY a.expires_at,a.id LIMIT ? FOR UPDATE OF a SKIP LOCKED""".stripMargin
    ) { p => bind(p, now, now, now.minusSeconds(300), batchSize); rows(p)(r => uuid(r, "id")) }

    var requestBudget = batchSize
    var deleted       = 0
    candidates.foreach { id =>
      // Callback exchange locks access before request. Never wait on a request owned by another transaction.
      val requests = statement(connection, "SELECT id FROM saml_request WHERE invitation_access_id=? ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED") {
        p => bind(p, id, requestBudget); rows(p)(_.getString("id"))
      }
      requests.foreach { request =>
        val _ = statement(connection, "UPDATE invitation_access SET saml_request_id=NULL WHERE id=? AND saml_request_id=?") { p =>
          bind(p, id, request); p.executeUpdate()
        }
        val _ = statement(connection, "DELETE FROM saml_request WHERE id=?") { p => bind(p, request); p.executeUpdate() }
        requestBudget -= 1
      }
      deleted += statement(
        connection,
        "DELETE FROM invitation_access a WHERE a.id=? AND NOT EXISTS (SELECT 1 FROM saml_request r WHERE r.invitation_access_id=a.id)"
      ) { p => bind(p, id); p.executeUpdate() }
    }
    deleted + batchSize - requestBudget
  }.transactionally
}
