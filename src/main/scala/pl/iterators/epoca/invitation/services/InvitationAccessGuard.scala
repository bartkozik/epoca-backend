package pl.iterators.epoca.invitation.services

import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID

/** Authorizes active Human Owners or superadmins using current database privileges under row locks. Users are locked first in UUID order, then
  * organization, then memberships in UUID order.
  */
final class InvitationAccessGuard {
  private def query[A](connection: Connection, sql: String)(bind: PreparedStatement => Unit)(read: ResultSet => A): Vector[A] = {
    val prepared = connection.prepareStatement(sql)
    try {
      bind(prepared)
      val results = prepared.executeQuery()
      try {
        val builder = Vector.newBuilder[A]
        while (results.next()) builder += read(results)
        builder.result()
      } finally results.close()
    } finally prepared.close()
  }

  def resolve(
    actorId: UserId,
    slug: OrganizationSlug,
    candidates: Vector[UserId]
  ): DBIO[Option[InvitationAccessGuard.Access]] = SimpleDBIO { ctx =>
    val connection = ctx.connection
    val userIds    = (Vector(actorId) ++ candidates).distinct.sortBy(_.toString)
    val users = userIds.flatMap { id =>
      query(connection, "SELECT id,archived_at,account_type,is_super_admin FROM \"user\" WHERE id = ? FOR SHARE")(_.setObject(1, id)) { r =>
        (
          UserId(r.getObject("id", classOf[UUID])),
          r.getTimestamp("archived_at") == null,
          r.getString("account_type") == "Human",
          r.getBoolean("is_super_admin")
        )
      }
    }
    if (!users.exists { case (id, active, human, _) => id == actorId && active && human }) None
    else {
      query(connection, "SELECT id FROM organization WHERE slug = ? AND archived_at IS NULL FOR UPDATE")(_.setString(1, slug))(r =>
        OrganizationId(r.getObject("id", classOf[UUID]))
      ).headOption.flatMap { organizationId =>
        val memberships = userIds.flatMap { id =>
          query(
            connection,
            "SELECT user_id,roles FROM organization_user WHERE organization_id = ? AND user_id = ? AND archived_at IS NULL FOR SHARE"
          ) { p =>
            p.setObject(1, organizationId); p.setObject(2, id)
          }(r => UserId(r.getObject("user_id", classOf[UUID])) -> r.getArray("roles").getArray.asInstanceOf[Array[String]].toVector)
        }
        val owner            = memberships.exists { case (id, roles) => id == actorId && roles.contains("Owner") }
        val superAdmin       = users.exists { case (id, _, _, admin) => id == actorId && admin }
        val activeIds        = users.collect { case (id, true, _, _) => id }.toSet
        val activeHumanUsers = users.collect { case (id, true, true, _) => id }.toSet
        Option.when(owner || superAdmin)(
          InvitationAccessGuard.Access(organizationId, memberships.map(_._1).toSet.intersect(activeIds), activeHumanUsers)
        )
      }
    }
  }
}
object InvitationAccessGuard {
  final case class Access(
    organizationId: OrganizationId,
    activeMembers: Set[UserId],
    activeHumanUsers: Set[UserId])
}
