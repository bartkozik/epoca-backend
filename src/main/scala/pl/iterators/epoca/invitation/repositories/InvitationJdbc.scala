package pl.iterators.epoca.invitation.repositories

import io.circe.Json
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Digest

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import java.util.UUID

private[repositories] object InvitationJdbc {
  def statement[A](connection: Connection, sql: String)(use: PreparedStatement => A): A = {
    val p = connection.prepareStatement(sql)
    try use(p)
    finally p.close()
  }
  def rows[A](p: PreparedStatement)(read: ResultSet => A): Vector[A] = {
    val r = p.executeQuery()
    try { val b = Vector.newBuilder[A]; while (r.next()) b += read(r); b.result() }
    finally r.close()
  }
  def bind(p: PreparedStatement, values: Any*): Unit = values.zipWithIndex.foreach { case (v, i) => value(p, i + 1, v) }
  private def value(
    p: PreparedStatement,
    i: Int,
    v: Any
  ): Unit = v match {
    case None       => p.setNull(i, Types.NULL)
    case Some(x)    => value(p, i, x)
    case x: Instant => p.setTimestamp(i, Timestamp.from(x))
    case x: Digest  => p.setBytes(i, x.toArray)
    case x: Json    => p.setObject(i, x.noSpaces, Types.OTHER)
    case x: Seq[_]  => p.setArray(i, p.getConnection.createArrayOf("text", x.map(_.toString).toArray[AnyRef]))
    case x          => p.setObject(i, x)
  }
  def uuid(r: ResultSet, n: String): UUID            = r.getObject(n, classOf[UUID])
  def instant(r: ResultSet, n: String): Instant      = r.getTimestamp(n).toInstant
  def time(r: ResultSet, n: String): Option[Instant] = Option(r.getTimestamp(n)).map(_.toInstant)
  def json(r: ResultSet, n: String): Option[Json]    = Option(r.getString(n)).map(parseJson)
  def parseJson(s: String): Json                     = io.circe.parser.parse(s).fold(throw _, identity)
}
