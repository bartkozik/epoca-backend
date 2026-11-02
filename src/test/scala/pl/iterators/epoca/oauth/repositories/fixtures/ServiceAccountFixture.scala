package pl.iterators.epoca.oauth.repositories.fixtures

import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientExpiresAt, OAuthClientId, OAuthClientLastUsedAt, OAuthClientPublicId}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserId
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationUserRepositoryImpl
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserFirstName, UserIsSuperAdmin, UserLastName}
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.Generators
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.jdbc.SetParameter

import java.sql.{Timestamp, Types}
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

final case class LegalServiceAccount(
  user: User,
  membership: OrganizationUser,
  oauthClientId: OAuthClientId,
  publicId: OAuthClientPublicId,
  expiresAt: Option[OAuthClientExpiresAt] = None,
  lastUsedAt: Option[OAuthClientLastUsedAt] = None)
final case class ServiceAccountIdentity(user: User, membership: OrganizationUser)

trait ServiceAccountFixture {
  deps: Generators =>

  implicit val ec: ExecutionContext
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))
  private implicit val setOptionalInstant: SetParameter[Option[Instant]] = SetParameter { (value, parameters) =>
    value match {
      case Some(instant) => parameters.setTimestamp(Timestamp.from(instant))
      case None          => parameters.setNull(Types.TIMESTAMP)
    }
  }

  private val fixtureUserRepository             = new UserRepositoryImpl()
  private val fixtureOrganizationUserRepository = new OrganizationUserRepositoryImpl()
  private val fixtureUserGenerator              = allGenerators[User].normal

  def withLegalServiceAccount(
    organization: Organization,
    createdBy: User,
    serviceAccount: User = fixtureUserGenerator.generate.copy(
      firstName = UserFirstName("Integration"),
      lastName = UserLastName(s"Fixture-${UUID.randomUUID()}"),
      isSuperAdmin = UserIsSuperAdmin(false),
      archivedAt = None,
      accountType = UserAccountType.ServiceAccount
    ),
    createdAt: Instant = Instant.now(),
    expiresAt: Option[OAuthClientExpiresAt] = None,
    lastUsedAt: Option[OAuthClientLastUsedAt] = None
  ): DBIO[LegalServiceAccount] = {
    val clientUuid      = UUID.randomUUID()
    val clientId        = OAuthClientId(clientUuid)
    val publicId        = OAuthClientPublicId(s"epoca_fixture_${UUID.randomUUID()}")
    val name            = s"Fixture ${UUID.randomUUID()}"
    val expiresAtValue  = expiresAt.map(_.asInstanceOf[Instant])
    val lastUsedAtValue = lastUsedAt.map(_.asInstanceOf[Instant])

    for {
      identity <- withServiceAccountIdentity(organization, serviceAccount)
      _ <- sqlu"""
        INSERT INTO oauth_client (
          id, client_id, organization_id, service_account_user_id, name, status,
          project_access_mode, secret_hash, secret_version, version, policy_version,
          created_at, created_by, modified_at, modified_by,
          secret_rotated_at, secret_rotated_by, revoked_at, revoked_by,
          expires_at, last_used_at
        ) VALUES (
          $clientUuid, $publicId, ${organization.id}, ${serviceAccount.id}, $name, 'Active',
          'AllProjects', 'fixture-bcrypt-hash', 1, 1, 1,
          $createdAt, ${createdBy.id}, $createdAt, ${createdBy.id},
          $createdAt, ${createdBy.id}, NULL, NULL,
          $expiresAtValue, $lastUsedAtValue
        )
      """
      _ <- sqlu"""INSERT INTO oauth_client_scope (oauth_client_id, scope) VALUES ($clientUuid, 'users:read')"""
      _ <- sqlu"SET CONSTRAINTS ALL IMMEDIATE"
      _ <- sqlu"SET CONSTRAINTS ALL DEFERRED"
    } yield LegalServiceAccount(identity.user, identity.membership, clientId, publicId, expiresAt, lastUsedAt)
  }

  def withServiceAccountIdentity(
    organization: Organization,
    serviceAccount: User = fixtureUserGenerator.generate.copy(
      firstName = UserFirstName("Integration"),
      lastName = UserLastName(s"Fixture-${UUID.randomUUID()}"),
      isSuperAdmin = UserIsSuperAdmin(false),
      archivedAt = None,
      accountType = UserAccountType.ServiceAccount
    )
  ): DBIO[ServiceAccountIdentity] = {
    val membership = OrganizationUser(OrganizationUserId.random, organization.id, serviceAccount.id, roles = Nil, archivedAt = None)
    for {
      insertedUser       <- fixtureUserRepository.insert(serviceAccount)
      insertedMembership <- fixtureOrganizationUserRepository.upsert(membership)
    } yield ServiceAccountIdentity(insertedUser, insertedMembership)
  }
}
