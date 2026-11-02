package pl.iterators.epoca.user.repositories

import pl.iterators.epoca.user.domain.UserDomain.IdentityType
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.{Resolution, canonical}
import pl.iterators.epoca.user.repositories.tables.{UserIdentityTable, UserTable}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.EmailCanonicalization

import scala.concurrent.ExecutionContext

class EmailIdentityRepositoryImpl(implicit ec: ExecutionContext) extends EmailIdentityRepository {
  private val identities = TableQuery[UserIdentityTable]
  private val users      = TableQuery[UserTable]

  override def lock(email: String): DBIO[Unit] = SimpleDBIO { context =>
    val statement = context.connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
    try {
      statement.setString(1, s"email:${canonical(email)}")
      val result = statement.executeQuery()
      result.close()
    } finally statement.close()
  }

  override def resolve(email: String): DBIO[Resolution] =
    identities
      .filter(i =>
        (i.`type` inSet Seq(IdentityType.MailAddress: IdentityType, IdentityType.Saml: IdentityType)) &&
          EmailCanonicalization.column(i.value.asColumnOf[String]) === canonical(email).bind
      )
      .join(users)
      .on(_.userId === _.id)
      .sortBy { case (identity, _) => (identity.userId, identity.id) }
      .result
      .map { rows =>
        rows.map(_._2.id).distinct match {
          case ids if ids.isEmpty   => Resolution.Empty
          case ids if ids.size == 1 => Resolution.Single(rows.head._2, rows.map(_._1))
          case _                    => Resolution.Ambiguous
        }
      }
}
