package pl.iterators.epoca.oauth.repositories

import io.circe.parser.parse
import io.circe.{Json, JsonObject}
import pl.iterators.epoca.oauth.domain.OAuthClient
import pl.iterators.epoca.oauth.domain.OAuthDomain._
import pl.iterators.epoca.oauth.repositories.OAuthClientRepository.{
  OAuthClientFilters,
  OAuthClientMutationResult,
  OAuthClientNotFound,
  OAuthClientRevoked,
  OAuthClientSnapshot,
  OAuthPolicyReplaceResult
}
import pl.iterators.epoca.oauth.repositories.tables.{
  OAuthClientProjectRow,
  OAuthClientProjectTable,
  OAuthClientScopeRow,
  OAuthClientScopeTable,
  OAuthClientTable
}
import pl.iterators.epoca.oauth.time.OAuthAuthoritativeTime
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import slick.jdbc.{SQLActionBuilder, SetParameter}
import slick.lifted.TableQuery

import java.sql.{Timestamp, Types}
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

class OAuthClientRepositoryImpl(authoritativeTime: OAuthAuthoritativeTime)(implicit ec: ExecutionContext)
    extends BaseRepository
    with OAuthClientRepository {
  private val clients  = TableQuery[OAuthClientTable]
  private val scopes   = TableQuery[OAuthClientScopeTable]
  private val projects = TableQuery[OAuthClientProjectTable]
  private implicit val setUuid: SetParameter[UUID] =
    SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))
  private implicit val setInstant: SetParameter[Instant] =
    SetParameter((value, parameters) => parameters.setTimestamp(Timestamp.from(value)))

  override def insert(snapshot: OAuthClientSnapshot): DBIO[OAuthClientSnapshot] =
    validateNewSnapshot(snapshot).flatMap { _ =>
      val projectIds = projectIdsFor(snapshot.projectAccess)
      for {
        inserted <- clients += snapshot.client
        _        <- requireSingleUpdate(snapshot.client.id, inserted)
        _        <- scopes ++= snapshot.scopes.toSeq.sortBy(_.value).map(OAuthClientScopeRow(snapshot.client.id, _))
        _        <- projects ++= projectIds.toSeq.sortBy(_.toString).map(OAuthClientProjectRow(snapshot.client.id, _))
      } yield snapshot
    }

  override private[oauth] def insertPreparedClientRow(client: OAuthClient): DBIO[Int] =
    clients += client

  override private[oauth] def insertPreparedScopeRow(id: OAuthClientId, scope: OAuthScope): DBIO[Int] =
    scopes += OAuthClientScopeRow(id, scope)

  override private[oauth] def insertPreparedProjectRow(id: OAuthClientId, projectId: ProjectId): DBIO[Int] =
    projects += OAuthClientProjectRow(id, projectId)

  override def findPublic(clientId: OAuthClientPublicId): DBIO[Option[OAuthClientSnapshot]] =
    concat(sql"SELECT ", snapshotJsonSql, sql"::text FROM oauth_client AS c WHERE c.client_id = ${clientId.asInstanceOf[String]}")
      .as[String]
      .map {
        case Seq(raw) => Some(decodeSnapshot(raw))
        case Seq()    => None
        case rows     => throw new IllegalStateException(s"Public OAuth client query returned ${rows.size} rows")
      }

  override def lock(id: OAuthClientId): DBIO[Option[OAuthClientSnapshot]] =
    clients.filter(_.id === id).forUpdate.result.headOption.flatMap(snapshotFor(_, lockChildren = true))

  override def list(filters: OAuthClientFilters, at: Instant): DBIO[Seq[OAuthClientSnapshot]] = {
    val limit  = filters.pageSizeAndNumber.fold(Long.MaxValue)(_.size.toLong)
    val offset = filters.pageSizeAndNumber.fold(0L)(_.offset.toLong)
    val query = concat(
      sql"""WITH paged_clients AS MATERIALIZED (
             SELECT c.*
             FROM oauth_client AS c """,
      effectiveStatusWhere(filters, at),
      sql"""
             ORDER BY c.created_at DESC, c.id DESC
             LIMIT $limit OFFSET $offset
           ) SELECT """,
      snapshotJsonSql,
      sql"::text FROM paged_clients AS c ORDER BY c.created_at DESC, c.id DESC"
    )

    query.as[String].map(_.map(decodeSnapshot))
  }

  override def count(filters: OAuthClientFilters, at: Instant): DBIO[Int] =
    concat(sql"SELECT count(*) FROM oauth_client AS c ", effectiveStatusWhere(filters, at)).as[Long].head.map(_.toInt)

  override def replacePolicy(
    id: OAuthClientId,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[OAuthClientSnapshot] =
    validatePolicy(replacementScopes, access).flatMap { _ =>
      lock(id).flatMap {
        case None          => DBIO.failed(OAuthClientNotFound(id))
        case Some(current) => ensureActive(current).flatMap(_ => applyPolicy(current, replacementScopes, access))
      }
    }

  override def replacePolicyAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[OAuthPolicyReplaceResult] =
    validatePolicy(replacementScopes, access).flatMap { _ =>
      lock(id).flatMap {
        case None => DBIO.failed(OAuthClientNotFound(id))
        case Some(current) =>
          ensureActive(current).flatMap { _ =>
            if (current.client.version.asInstanceOf[Long] != expectedVersion)
              DBIO.successful(OAuthPolicyReplaceResult.Stale(current.client.version.asInstanceOf[Long]))
            else applyPolicy(current, replacementScopes, access).map(OAuthPolicyReplaceResult.Applied)
          }
      }
    }

  override def replaceSecret(id: OAuthClientId, hash: String): DBIO[OAuthClientSnapshot] =
    if (hash.trim.isEmpty) DBIO.failed(new IllegalArgumentException("OAuth client secret hash must not be blank"))
    else
      lock(id).flatMap {
        case None => DBIO.failed(OAuthClientNotFound(id))
        case Some(current) =>
          ensureActive(current).flatMap(_ => authoritativeTime.now.flatMap(now => applySecret(current, hash, now, current.client.modifiedBy)))
      }

  override def updateAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    name: OAuthClientName,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess,
    expiresAt: Option[OAuthClientExpiresAt],
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientMutationResult] =
    validatePolicy(replacementScopes, access).flatMap { _ =>
      lock(id).flatMap {
        case None => DBIO.failed(OAuthClientNotFound(id))
        case Some(current) =>
          ensureActive(current).flatMap { _ =>
            if (current.client.version.asInstanceOf[Long] != expectedVersion)
              DBIO.successful(OAuthClientMutationResult.Stale(current.client.version.asInstanceOf[Long]))
            else applyUpdate(current, name, replacementScopes, access, expiresAt, at, by).map(OAuthClientMutationResult.Applied)
          }
      }
    }

  override def touchLastUsedAt(
    clientId: OAuthClientId,
    now: Instant,
    minInterval: FiniteDuration
  ): DBIO[Boolean] =
    if (minInterval < Duration.Zero)
      DBIO.failed(new IllegalArgumentException("OAuth client last-used minimum interval must not be negative"))
    else {
      val allowedBeforeOrAt = now.minusNanos(minInterval.toNanos)
      sqlu"""WITH candidate AS MATERIALIZED (
               SELECT id
               FROM oauth_client
               WHERE id = ${clientId.asInstanceOf[UUID]}
                 AND (last_used_at IS NULL OR last_used_at <= $allowedBeforeOrAt)
               FOR UPDATE SKIP LOCKED
             )
             UPDATE oauth_client AS client
             SET last_used_at = $now
             WHERE client.id = (SELECT id FROM candidate)
               AND (client.last_used_at IS NULL OR client.last_used_at <= $allowedBeforeOrAt)""".map(_ == 1)
    }

  override def rotateSecretAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    hash: String,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientMutationResult] =
    validateSecretHash(hash).flatMap { _ =>
      lock(id).flatMap {
        case None => DBIO.failed(OAuthClientNotFound(id))
        case Some(current) =>
          ensureActive(current).flatMap { _ =>
            if (current.client.version.asInstanceOf[Long] != expectedVersion)
              DBIO.successful(OAuthClientMutationResult.Stale(current.client.version.asInstanceOf[Long]))
            else applySecret(current, hash, at, by).map(OAuthClientMutationResult.Applied)
          }
      }
    }

  override def revoke(
    id: OAuthClientId,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientSnapshot] =
    lock(id).flatMap {
      case None          => DBIO.failed(OAuthClientNotFound(id))
      case Some(current) => ensureActive(current).flatMap(_ => applyRevoke(current, at, by))
    }

  override def revokeAtVersion(
    id: OAuthClientId,
    expectedVersion: Long,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientMutationResult] =
    lock(id).flatMap {
      case None => DBIO.failed(OAuthClientNotFound(id))
      case Some(current) =>
        ensureActive(current).flatMap { _ =>
          if (current.client.version.asInstanceOf[Long] != expectedVersion)
            DBIO.successful(OAuthClientMutationResult.Stale(current.client.version.asInstanceOf[Long]))
          else applyRevoke(current, at, by).map(OAuthClientMutationResult.Applied)
        }
    }

  private def effectiveStatusWhere(filters: OAuthClientFilters, at: Instant): SQLActionBuilder = {
    val status = filters.status.map(_.entryName)
    sql"""WHERE c.organization_id = ${filters.organizationId.asInstanceOf[UUID]}
           AND (
             ${status}::text IS NULL
             OR CASE
                  WHEN c.status = 'Revoked' THEN 'Revoked'
                  WHEN c.expires_at IS NOT NULL AND c.expires_at <= $at THEN 'Expired'
                  ELSE 'Active'
                END = ${status}::text
           )"""
  }

  private def snapshotFor(client: Option[OAuthClient], lockChildren: Boolean): DBIO[Option[OAuthClientSnapshot]] =
    client match {
      case None        => DBIO.successful(None)
      case Some(value) => loadSnapshot(value, lockChildren).map(Some(_))
    }

  private def loadSnapshot(client: OAuthClient, lockChildren: Boolean): DBIO[OAuthClientSnapshot] = {
    val scopeQuery    = scopes.filter(_.oauthClientId === client.id)
    val projectQuery  = projects.filter(_.oauthClientId === client.id).sortBy(_.projectId)
    val scopeAction   = if (lockChildren) scopeQuery.forUpdate.result else scopeQuery.result
    val projectAction = if (lockChildren) projectQuery.forUpdate.result else projectQuery.result
    for {
      scopeRows   <- scopeAction
      projectRows <- projectAction
      access <- client.projectAccessMode match {
                  case OAuthProjectAccessMode.AllProjects => DBIO.successful(OAuthProjectAccess.AllProjects)
                  case OAuthProjectAccessMode.SelectedProjects if projectRows.nonEmpty =>
                    DBIO.successful(OAuthProjectAccess.SelectedProjects(projectRows.map(_.projectId).toSet))
                  case OAuthProjectAccessMode.SelectedProjects =>
                    DBIO.failed(new IllegalStateException(s"SelectedProjects client ${client.id} has no persisted projects"))
                }
      snapshot <-
        if (scopeRows.nonEmpty) DBIO.successful(OAuthClientSnapshot(client, scopeRows.map(_.scope).toSet, access))
        else DBIO.failed(new IllegalStateException(s"OAuth client ${client.id} has no persisted scopes"))
    } yield snapshot
  }

  private val snapshotJsonSql: SQLActionBuilder =
    sql"""jsonb_build_object(
           'id', c.id,
           'clientId', c.client_id,
           'organizationId', c.organization_id,
           'serviceAccountUserId', c.service_account_user_id,
           'name', c.name,
           'status', c.status,
           'projectAccessMode', c.project_access_mode,
           'secretHash', c.secret_hash,
           'secretVersion', c.secret_version,
           'version', c.version,
           'policyVersion', c.policy_version,
           'createdAtEpoch', extract(epoch from c.created_at),
           'createdBy', c.created_by,
           'modifiedAtEpoch', extract(epoch from c.modified_at),
           'modifiedBy', c.modified_by,
           'secretRotatedAtEpoch', extract(epoch from c.secret_rotated_at),
           'secretRotatedBy', c.secret_rotated_by,
           'revokedAtEpoch', extract(epoch from c.revoked_at),
           'revokedBy', c.revoked_by,
           'expiresAtEpoch', extract(epoch from c.expires_at),
           'lastUsedAtEpoch', extract(epoch from c.last_used_at),
           'scopes', (
             SELECT coalesce(jsonb_agg(s.scope ORDER BY s.scope), '[]'::jsonb)
             FROM oauth_client_scope AS s
             WHERE s.oauth_client_id = c.id
           ),
           'projectIds', (
             SELECT coalesce(jsonb_agg(p.project_id ORDER BY p.project_id), '[]'::jsonb)
             FROM oauth_client_project AS p
             WHERE p.oauth_client_id = c.id
           )
         )"""

  private def decodeSnapshot(raw: String): OAuthClientSnapshot = {
    val jsonObject = Option(raw)
      .toRight("OAuth client snapshot was SQL NULL")
      .flatMap(value => parse(value).left.map(error => s"OAuth client snapshot was malformed JSON: ${error.message}"))
      .flatMap(_.asObject.toRight("OAuth client snapshot must be a JSON object"))
      .fold(message => throw new IllegalStateException(message), identity)

    val mode = OAuthProjectAccessMode
      .withNameOption(requiredString(jsonObject, "projectAccessMode"))
      .getOrElse(throw new IllegalStateException("OAuth client snapshot contained an unknown projectAccessMode"))
    val scopeValues = requiredArray(jsonObject, "scopes").map { json =>
      json.asString
        .flatMap(OAuthScope.fromValue)
        .getOrElse(throw new IllegalStateException("OAuth client snapshot contained an unknown or non-string scope"))
    }
    if (scopeValues.isEmpty) throw new IllegalStateException("OAuth client snapshot contained no scopes")
    if (scopeValues.distinct.size != scopeValues.size) throw new IllegalStateException("OAuth client snapshot contained duplicate scopes")

    val projectIds = requiredArray(jsonObject, "projectIds").map(json => ProjectId(requiredUuid(json, "projectIds")))
    if (projectIds.distinct.size != projectIds.size) throw new IllegalStateException("OAuth client snapshot contained duplicate projectIds")
    val projectAccess: OAuthProjectAccess = mode match {
      case OAuthProjectAccessMode.AllProjects if projectIds.isEmpty => OAuthProjectAccess.AllProjects
      case OAuthProjectAccessMode.AllProjects =>
        throw new IllegalStateException("AllProjects OAuth client snapshot contained projectIds")
      case OAuthProjectAccessMode.SelectedProjects if projectIds.nonEmpty => OAuthProjectAccess.SelectedProjects(projectIds.toSet)
      case OAuthProjectAccessMode.SelectedProjects =>
        throw new IllegalStateException("SelectedProjects OAuth client snapshot contained no projectIds")
    }

    val client = OAuthClient(
      id = OAuthClientId(requiredUuid(jsonObject, "id")),
      clientId = OAuthClientPublicId(requiredString(jsonObject, "clientId")),
      organizationId = OrganizationId(requiredUuid(jsonObject, "organizationId")),
      serviceAccountUserId = UserId(requiredUuid(jsonObject, "serviceAccountUserId")),
      name = OAuthClientName(requiredString(jsonObject, "name")),
      status = OAuthClientStatus
        .withNameOption(requiredString(jsonObject, "status"))
        .getOrElse(throw new IllegalStateException("OAuth client snapshot contained an unknown status")),
      projectAccessMode = mode,
      secretHash = OAuthClientSecretHash(requiredString(jsonObject, "secretHash")),
      secretVersion = OAuthClientSecretVersion(requiredLong(jsonObject, "secretVersion")),
      version = OAuthClientVersion(requiredLong(jsonObject, "version")),
      policyVersion = OAuthClientPolicyVersion(requiredLong(jsonObject, "policyVersion")),
      createdAt = OAuthClientCreatedAt(requiredInstant(jsonObject, "createdAtEpoch")),
      createdBy = UserId(requiredUuid(jsonObject, "createdBy")),
      modifiedAt = OAuthClientModifiedAt(requiredInstant(jsonObject, "modifiedAtEpoch")),
      modifiedBy = UserId(requiredUuid(jsonObject, "modifiedBy")),
      secretRotatedAt = OAuthClientSecretRotatedAt(requiredInstant(jsonObject, "secretRotatedAtEpoch")),
      secretRotatedBy = UserId(requiredUuid(jsonObject, "secretRotatedBy")),
      revokedAt = optionalInstant(jsonObject, "revokedAtEpoch").map(value => OAuthClientRevokedAt(value)),
      revokedBy = optionalUuid(jsonObject, "revokedBy").map(value => UserId(value)),
      expiresAt = optionalInstant(jsonObject, "expiresAtEpoch").map(value => OAuthClientExpiresAt(value)),
      lastUsedAt = optionalInstant(jsonObject, "lastUsedAtEpoch").map(value => OAuthClientLastUsedAt(value))
    )
    OAuthClientSnapshot(client, scopeValues.toSet, projectAccess)
  }

  private def requiredJson(jsonObject: JsonObject, key: String): Json =
    jsonObject(key).filterNot(_.isNull).getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was missing or null"))

  private def requiredString(jsonObject: JsonObject, key: String): String =
    requiredJson(jsonObject, key).asString.getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was not a string"))

  private def requiredLong(jsonObject: JsonObject, key: String): Long =
    requiredJson(jsonObject, key).asNumber
      .flatMap(_.toLong)
      .getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was not a long"))

  private def requiredArray(jsonObject: JsonObject, key: String): Vector[Json] =
    requiredJson(jsonObject, key).asArray.getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was not an array"))

  private def requiredUuid(jsonObject: JsonObject, key: String): UUID =
    requiredUuid(requiredJson(jsonObject, key), key)

  private def requiredUuid(json: Json, key: String): UUID =
    json.asString
      .flatMap(raw => Try(UUID.fromString(raw)).toOption.filter(_.toString == raw))
      .getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was not a canonical UUID"))

  private def optionalUuid(jsonObject: JsonObject, key: String): Option[UUID] =
    jsonObject(key) match {
      case None                      => None
      case Some(json) if json.isNull => None
      case Some(json)                => Some(requiredUuid(json, key))
    }

  private def requiredInstant(jsonObject: JsonObject, key: String): Instant =
    optionalInstant(jsonObject, key).getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was missing or null"))

  private def optionalInstant(jsonObject: JsonObject, key: String): Option[Instant] =
    jsonObject(key) match {
      case None                      => None
      case Some(json) if json.isNull => None
      case Some(json) =>
        val epoch = json.asNumber
          .flatMap(_.toBigDecimal)
          .getOrElse(throw new IllegalStateException(s"OAuth client snapshot field $key was not an epoch number"))
        val seconds = epoch.setScale(0, BigDecimal.RoundingMode.FLOOR).toLong
        val nanos   = ((epoch - BigDecimal(seconds)) * BigDecimal(1000000000L)).bigDecimal.longValueExact()
        Some(Instant.ofEpochSecond(seconds, nanos))
    }

  private def concat(builders: SQLActionBuilder*): SQLActionBuilder =
    SQLActionBuilder(
      builders.map(_.sql).mkString,
      SetParameter { (_, positionedParameters) =>
        builders.foreach(_.setParameter((), positionedParameters))
      }
    )

  private def validatePolicy(replacementScopes: Set[OAuthScope], access: OAuthProjectAccess): DBIO[Unit] =
    if (replacementScopes.isEmpty) DBIO.failed(new IllegalArgumentException("OAuth client must have at least one scope"))
    else
      access match {
        case OAuthProjectAccess.SelectedProjects(projectIds) if projectIds.isEmpty =>
          DBIO.failed(new IllegalArgumentException("SelectedProjects must contain at least one project"))
        case _ => DBIO.successful(())
      }

  private def validateNewSnapshot(snapshot: OAuthClientSnapshot): DBIO[Unit] = {
    val expectedMode = modeFor(snapshot.projectAccess)
    if (snapshot.client.status != OAuthClientStatus.Active)
      DBIO.failed(new IllegalArgumentException("New OAuth client must be Active"))
    else if (snapshot.client.projectAccessMode != expectedMode)
      DBIO.failed(new IllegalArgumentException("OAuth client project mode must match its project policy"))
    else validatePolicy(snapshot.scopes, snapshot.projectAccess)
  }

  private def validateSecretHash(hash: String): DBIO[Unit] =
    if (hash.trim.isEmpty) DBIO.failed(new IllegalArgumentException("OAuth client secret hash must not be blank"))
    else DBIO.successful(())

  private def ensureActive(current: OAuthClientSnapshot): DBIO[Unit] =
    current.client.status match {
      case OAuthClientStatus.Active  => DBIO.successful(())
      case OAuthClientStatus.Revoked => DBIO.failed(OAuthClientRevoked(current.client.id))
    }

  private def modeFor(access: OAuthProjectAccess): OAuthProjectAccessMode =
    access match {
      case OAuthProjectAccess.AllProjects         => OAuthProjectAccessMode.AllProjects
      case _: OAuthProjectAccess.SelectedProjects => OAuthProjectAccessMode.SelectedProjects
    }

  private def projectIdsFor(access: OAuthProjectAccess): Set[ProjectId] =
    access match {
      case OAuthProjectAccess.AllProjects                  => Set.empty
      case OAuthProjectAccess.SelectedProjects(projectIds) => projectIds
    }

  private def applyUpdate(
    current: OAuthClientSnapshot,
    name: OAuthClientName,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess,
    expiresAt: Option[OAuthClientExpiresAt],
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientSnapshot] = {
    val delegatedPolicyChanged = current.scopes != replacementScopes || current.projectAccess != access
    val nameChanged            = current.client.name != name
    val expiryChanged          = current.client.expiresAt != expiresAt
    val policyChanged          = delegatedPolicyChanged || expiryChanged
    if (!nameChanged && !policyChanged) DBIO.successful(current)
    else {
      val updatedClient = current.client.copy(
        name = name,
        projectAccessMode = modeFor(access),
        expiresAt = expiresAt,
        version = OAuthClientVersion(current.client.version.asInstanceOf[Long] + 1L),
        policyVersion = OAuthClientPolicyVersion(current.client.policyVersion.asInstanceOf[Long] + (if (policyChanged) 1L else 0L)),
        modifiedAt = OAuthClientModifiedAt(at),
        modifiedBy = by
      )
      for {
        updated <- clients.filter(_.id === current.client.id).update(updatedClient)
        _       <- requireSingleUpdate(current.client.id, updated)
        _ <-
          if (delegatedPolicyChanged) replacePolicyRows(current.client.id, replacementScopes, access)
          else DBIO.successful(())
      } yield OAuthClientSnapshot(updatedClient, replacementScopes, access)
    }
  }

  private def applySecret(
    current: OAuthClientSnapshot,
    hash: String,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientSnapshot] = {
    val updatedClient = current.client.copy(
      secretHash = OAuthClientSecretHash(hash),
      secretVersion = OAuthClientSecretVersion(current.client.secretVersion.asInstanceOf[Long] + 1L),
      version = OAuthClientVersion(current.client.version.asInstanceOf[Long] + 1L),
      modifiedAt = OAuthClientModifiedAt(at),
      modifiedBy = by,
      secretRotatedAt = OAuthClientSecretRotatedAt(at),
      secretRotatedBy = by
    )
    clients
      .filter(_.id === current.client.id)
      .update(updatedClient)
      .flatMap(updated => requireSingleUpdate(current.client.id, updated).map(_ => current.copy(client = updatedClient)))
  }

  private def applyRevoke(
    current: OAuthClientSnapshot,
    at: Instant,
    by: UserId
  ): DBIO[OAuthClientSnapshot] = {
    val updatedClient = current.client.copy(
      status = OAuthClientStatus.Revoked,
      version = OAuthClientVersion(current.client.version.asInstanceOf[Long] + 1L),
      policyVersion = OAuthClientPolicyVersion(current.client.policyVersion.asInstanceOf[Long] + 1L),
      modifiedAt = OAuthClientModifiedAt(at),
      modifiedBy = by,
      revokedAt = Some(OAuthClientRevokedAt(at)),
      revokedBy = Some(by)
    )
    clients
      .filter(_.id === current.client.id)
      .update(updatedClient)
      .flatMap(updated => requireSingleUpdate(current.client.id, updated).map(_ => current.copy(client = updatedClient)))
  }

  private def applyPolicy(
    current: OAuthClientSnapshot,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[OAuthClientSnapshot] =
    if (current.scopes == replacementScopes && current.projectAccess == access) DBIO.successful(current)
    else {
      val mode = modeFor(access)
      for {
        now <- authoritativeTime.now
        updatedClient = current.client.copy(
                          projectAccessMode = mode,
                          version = OAuthClientVersion(current.client.version.asInstanceOf[Long] + 1L),
                          policyVersion = OAuthClientPolicyVersion(current.client.policyVersion.asInstanceOf[Long] + 1L),
                          modifiedAt = OAuthClientModifiedAt(now)
                        )
        updated <- clients.filter(_.id === current.client.id).update(updatedClient)
        _       <- requireSingleUpdate(current.client.id, updated)
        _       <- replacePolicyRows(current.client.id, replacementScopes, access)
      } yield OAuthClientSnapshot(updatedClient, replacementScopes, access)
    }

  private def replacePolicyRows(
    id: OAuthClientId,
    replacementScopes: Set[OAuthScope],
    access: OAuthProjectAccess
  ): DBIO[Unit] = {
    val projectIds = projectIdsFor(access)
    for {
      _ <- scopes.filter(_.oauthClientId === id).delete
      _ <- scopes ++= replacementScopes.toSeq.sortBy(_.value).map(OAuthClientScopeRow(id, _))
      _ <- projects.filter(_.oauthClientId === id).delete
      _ <- projects ++= projectIds.toSeq.sortBy(_.toString).map(OAuthClientProjectRow(id, _))
    } yield ()
  }

  private def requireSingleUpdate(id: OAuthClientId, updated: Int): DBIO[Unit] =
    if (updated == 1) DBIO.successful(())
    else DBIO.failed(new IllegalStateException(s"OAuth client $id update affected $updated rows"))
}
