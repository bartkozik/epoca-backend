package pl.iterators.epoca.utils.db

import org.postgresql.util.PSQLException
import pl.iterators.epoca.auth.domain.UserLoginToken
import pl.iterators.epoca.auth.repositories.{UserLoginTokenRepositoryImpl, UserPasswordRepositoryImpl}
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.{UserIdentityRepositoryImpl, UserRepositoryImpl}
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.jdbc.{GetResult, SetParameter}

import java.sql.Types
import java.util.UUID
import scala.util.{Failure, Success, Try}

class ForeignKeyConstraintsSpec extends PgRepositorySpec {

  import ForeignKeyConstraintsSpec._

  private implicit val setUuid: SetParameter[UUID] = SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  private implicit val getForeignKey: GetResult[ForeignKey] = GetResult { result =>
    ForeignKey(
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextString(),
      result.nextBoolean()
    )
  }

  private implicit val getIndex: GetResult[Index] = GetResult { result =>
    Index(result.nextString(), result.nextString())
  }

  private val userRepository           = new UserRepositoryImpl()
  private val userIdentityRepository   = new UserIdentityRepositoryImpl()
  private val userPasswordRepository   = new UserPasswordRepositoryImpl()
  private val userLoginTokenRepository = new UserLoginTokenRepositoryImpl()

  private val userGenerator: Generator[User]                     = allGenerators[User].normal
  private val userIdentityGenerator: Generator[UserIdentity]     = allGenerators[UserIdentity].normal
  private val userLoginTokenGenerator: Generator[UserLoginToken] = allGenerators[UserLoginToken].normal

  "foreign-key constraints" should {
    "have the exact required catalog definitions" in withDatabase {
      foreignKeyConstraints.map { actual =>
        actual should containTheSameElementsAs(
          Seq(
            ForeignKey("refresh_token_user_id_fkey", "public", "refresh_token", "user_id", "public", "user", "id", "a", true),
            ForeignKey("user_identity_user_id_fkey", "public", "user_identity", "user_id", "public", "user", "id", "a", true),
            ForeignKey(
              "user_login_token_user_identity_id_fkey",
              "public",
              "user_login_token",
              "user_identity_id",
              "public",
              "user_identity",
              "id",
              "c",
              true
            ),
            ForeignKey(
              "user_password_user_identity_id_fkey",
              "public",
              "user_password",
              "user_identity_id",
              "public",
              "user_identity",
              "id",
              "c",
              true
            ),
            ForeignKey("saml_request_organization_id_fkey", "public", "saml_request", "organization_id", "public", "organization", "id", "a", true),
            ForeignKey(
              "saml_request_invitation_access_id_fkey",
              "public",
              "saml_request",
              "invitation_access_id",
              "public",
              "invitation_access",
              "id",
              "a",
              true
            ),
            ForeignKey("saml_request_user_id_fkey", "public", "saml_request", "user_id", "public", "user", "id", "a", true),
            ForeignKey(
              "sendgrid_mail_event_mail_uuid_fkey",
              "public",
              "sendgrid_mail_event",
              "mail_uuid",
              "public",
              "mail_history",
              "mail_uuid",
              "a",
              true
            )
          )
        )
      }
    }

    "have an index for user login token identities" in withDatabase {
      userLoginTokenIdentityIndex.map { actual =>
        actual shouldEqual Seq(
          Index(
            "user_login_token_user_identity_id_idx",
            "CREATE INDEX user_login_token_user_identity_id_idx ON public.user_login_token USING btree (user_identity_id)"
          )
        )
      }
    }

    "reject an orphan refresh token" in {
      assertForeignKeyViolation(insertRefreshToken(UUID.randomUUID()))
    }

    "reject an orphan user identity" in {
      assertForeignKeyViolation(insertUserIdentity(UUID.randomUUID(), UUID.randomUUID()))
    }

    "reject an orphan user login token" in {
      assertForeignKeyViolation(insertUserLoginToken(UUID.randomUUID(), UUID.randomUUID()))
    }

    "reject an orphan user password" in {
      assertForeignKeyViolation(insertUserPassword(UUID.randomUUID()))
    }

    "reject an orphan SendGrid mail event" in {
      assertForeignKeyViolation(insertSendgridMailEvent(UUID.randomUUID()))
    }

    "cascades password and login token deletion when an identity is purged" in withDatabase {
      val user     = userGenerator.generate.copy(archivedAt = None)
      val identity = userIdentityGenerator.generate.copy(userId = user.id, main = UserIdentityMain(false))
      val password = allGenerators[pl.iterators.epoca.auth.domain.UserPassword].normal.generate.copy(userIdentityId = identity.id)
      val token    = userLoginTokenGenerator.generate.copy(userIdentityId = identity.id)

      for {
        _ <- userRepository.insert(user)
        _ <- userIdentityRepository.insert(identity)
        _ <- userPasswordRepository.upsert(password)
        _ <- userLoginTokenRepository.insert(token)
        _ <- userIdentityRepository.purge(pl.iterators.epoca.user.repositories.UserIdentityRepository.UserIdentityFilters(id = Some(identity.id)))
        foundPassword <- userPasswordRepository.find(identity.id)
        foundToken <-
          userLoginTokenRepository.find(pl.iterators.epoca.auth.repositories.UserLoginTokenRepository.UserLoginTokenFilters(id = Some(token.id)))
      } yield (foundPassword, foundToken) shouldEqual (None, None)
    }

    "rejects deleting a user referenced only by a refresh token" in withDatabase {
      val user = userGenerator.generate.copy(archivedAt = None)

      for {
        _      <- userRepository.insert(user)
        _      <- insertRefreshToken(user.id.asInstanceOf[UUID])
        result <- sqlu"""DELETE FROM "user" WHERE id = ${user.id.asInstanceOf[UUID]}""".asTry
      } yield assertForeignKeyViolation(result)
    }

    "rejects deleting a user referenced only by an identity" in withDatabase {
      val user     = userGenerator.generate.copy(archivedAt = None)
      val identity = userIdentityGenerator.generate.copy(userId = user.id)

      for {
        _      <- userRepository.insert(user)
        _      <- userIdentityRepository.insert(identity)
        result <- sqlu"""DELETE FROM "user" WHERE id = ${user.id.asInstanceOf[UUID]}""".asTry
      } yield assertForeignKeyViolation(result)
    }

    "rejects deleting mail history referenced only by a SendGrid mail event" in withDatabase {
      val mailUuid = UUID.randomUUID()

      for {
        _      <- insertMailHistory(mailUuid)
        _      <- insertSendgridMailEvent(mailUuid)
        result <- sqlu"""DELETE FROM mail_history WHERE mail_uuid = $mailUuid""".asTry
      } yield assertForeignKeyViolation(result)
    }
  }

  private def foreignKeyConstraints =
    sql"""
      SELECT
        constraint_row.conname,
        child_schema.nspname,
        child_table.relname,
        child_column.attname,
        parent_schema.nspname,
        parent_table.relname,
        parent_column.attname,
        constraint_row.confdeltype::text,
        constraint_row.convalidated
      FROM pg_constraint constraint_row
      JOIN pg_class child_table ON child_table.oid = constraint_row.conrelid
      JOIN pg_namespace child_schema ON child_schema.oid = child_table.relnamespace
      JOIN pg_class parent_table ON parent_table.oid = constraint_row.confrelid
      JOIN pg_namespace parent_schema ON parent_schema.oid = parent_table.relnamespace
      JOIN LATERAL unnest(constraint_row.conkey) WITH ORDINALITY AS child_key(attnum, ordinality) ON TRUE
      JOIN LATERAL unnest(constraint_row.confkey) WITH ORDINALITY AS parent_key(attnum, ordinality)
        ON parent_key.ordinality = child_key.ordinality
      JOIN pg_attribute child_column ON child_column.attrelid = child_table.oid AND child_column.attnum = child_key.attnum
      JOIN pg_attribute parent_column ON parent_column.attrelid = parent_table.oid AND parent_column.attnum = parent_key.attnum
      WHERE constraint_row.contype = 'f'
        AND child_schema.nspname = 'public'
        AND child_table.relname IN (
          'refresh_token',
          'user_identity',
          'user_login_token',
          'user_password',
          'saml_request',
          'sendgrid_mail_event'
        )
      ORDER BY constraint_row.conname, child_key.ordinality
    """.as[ForeignKey]

  private def userLoginTokenIdentityIndex =
    sql"""
      SELECT indexname, indexdef
      FROM pg_indexes
      WHERE schemaname = 'public'
        AND tablename = 'user_login_token'
        AND indexname = 'user_login_token_user_identity_id_idx'
    """.as[Index]

  private def assertForeignKeyViolation(action: DBIO[Int]) =
    withDatabase(action.asTry) match {
      case Failure(error: PSQLException) => error.getSQLState shouldEqual "23503"
      case Failure(error)                => error.getClass.getName shouldEqual classOf[PSQLException].getName
      case Success(_)                    => "accepted statement" shouldEqual "SQLSTATE 23503 foreign-key violation"
    }

  private def assertForeignKeyViolation(result: Try[Int]) =
    result match {
      case Failure(error: PSQLException) => error.getSQLState shouldEqual "23503"
      case Failure(error)                => error.getClass.getName shouldEqual classOf[PSQLException].getName
      case Success(_)                    => "accepted statement" shouldEqual "SQLSTATE 23503 foreign-key violation"
    }

  private def insertRefreshToken(userId: UUID) = {
    val deviceId = s"device-${UUID.randomUUID()}"
    val value    = s"refresh-${UUID.randomUUID()}"

    sqlu"""
      INSERT INTO refresh_token (user_id, device_id, user_agent, value, authed_by, last_authed_at, created_at, expired_at)
      VALUES ($userId, $deviceId, NULL, $value, ARRAY[]::uuid[], NOW(), NOW(), NULL)
    """
  }

  private def insertUserIdentity(id: UUID, userId: UUID) = {
    val value = s"${UUID.randomUUID()}@example.test"

    sqlu"""
      INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
      VALUES ($id, $userId, 'MailAddress', $value, FALSE, NULL, TRUE, NULL, NOW())
    """
  }

  private def insertUserLoginToken(id: UUID, userIdentityId: UUID) = {
    val loginToken = s"login-${UUID.randomUUID()}"

    sqlu"""
      INSERT INTO user_login_token (id, user_identity_id, login_token, valid_to)
      VALUES ($id, $userIdentityId, $loginToken, NULL)
    """
  }

  private def insertUserPassword(userIdentityId: UUID) = {
    val password = s"password-${UUID.randomUUID()}"

    sqlu"""INSERT INTO user_password (user_identity_id, password) VALUES ($userIdentityId, $password)"""
  }

  private def insertMailHistory(mailUuid: UUID) = {
    val email = s"${UUID.randomUUID()}@example.test"

    sqlu"""
      INSERT INTO mail_history (mail_uuid, mail_type, email, created, api_success, delivered, clicked, opened)
      VALUES ($mailUuid, 'user_magic_login_link', $email, NOW(), NULL, NULL, NULL, NULL)
    """
  }

  private def insertSendgridMailEvent(mailUuid: UUID) = {
    val email = s"${UUID.randomUUID()}@example.test"

    sqlu"""INSERT INTO sendgrid_mail_event (mail_uuid, environment, email) VALUES ($mailUuid, 'test', $email)"""
  }
}

private object ForeignKeyConstraintsSpec {
  final case class ForeignKey(
    name: String,
    childSchema: String,
    childTable: String,
    childColumn: String,
    parentSchema: String,
    parentTable: String,
    parentColumn: String,
    deleteType: String,
    validated: Boolean)

  final case class Index(name: String, definition: String)
}
