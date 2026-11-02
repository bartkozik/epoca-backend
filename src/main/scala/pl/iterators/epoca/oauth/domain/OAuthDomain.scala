package pl.iterators.epoca.oauth.domain

import enumeratum.{Enum, EnumEntry}
import io.circe.{Decoder, Encoder, Json}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.util.Try

@tagged trait Domain {
  trait OAuthClientIdTag
  type OAuthClientId = UUID @@ OAuthClientIdTag

  trait OAuthClientPublicIdTag
  type OAuthClientPublicId = String @@ OAuthClientPublicIdTag

  trait OAuthClientNameTag
  type OAuthClientName = String @@ OAuthClientNameTag

  trait OAuthClientSecretHashTag
  type OAuthClientSecretHash = String @@ OAuthClientSecretHashTag

  trait OAuthClientSecretTag
  type OAuthClientSecret = String @@ OAuthClientSecretTag

  trait OAuthClientSecretVersionTag
  type OAuthClientSecretVersion = Long @@ OAuthClientSecretVersionTag

  trait OAuthClientVersionTag
  type OAuthClientVersion = Long @@ OAuthClientVersionTag

  trait OAuthClientPolicyVersionTag
  type OAuthClientPolicyVersion = Long @@ OAuthClientPolicyVersionTag

  trait OAuthClientCreatedAtTag
  type OAuthClientCreatedAt = Instant @@ OAuthClientCreatedAtTag

  trait OAuthClientModifiedAtTag
  type OAuthClientModifiedAt = Instant @@ OAuthClientModifiedAtTag

  trait OAuthClientSecretRotatedAtTag
  type OAuthClientSecretRotatedAt = Instant @@ OAuthClientSecretRotatedAtTag

  trait OAuthClientRevokedAtTag
  type OAuthClientRevokedAt = Instant @@ OAuthClientRevokedAtTag

  trait OAuthClientExpiresAtTag
  type OAuthClientExpiresAt = Instant @@ OAuthClientExpiresAtTag

  trait OAuthClientLastUsedAtTag
  type OAuthClientLastUsedAt = Instant @@ OAuthClientLastUsedAtTag

  trait OAuthAuditEventIdTag
  type OAuthAuditEventId = UUID @@ OAuthAuditEventIdTag

  trait OAuthAuditOccurredAtTag
  type OAuthAuditOccurredAt = Instant @@ OAuthAuditOccurredAtTag

  trait OAuthAuditCorrelationIdTag
  type OAuthAuditCorrelationId = String @@ OAuthAuditCorrelationIdTag

  trait OAuthTokenIdTag
  type OAuthTokenId = UUID @@ OAuthTokenIdTag

  trait OAuthRateLimitBucketKeyTag
  type OAuthRateLimitBucketKey = String @@ OAuthRateLimitBucketKeyTag

  trait OAuthRateLimitAvailableTokensTag
  type OAuthRateLimitAvailableTokens = Double @@ OAuthRateLimitAvailableTokensTag

  trait OAuthRateLimitLastRefilledAtTag
  type OAuthRateLimitLastRefilledAt = Instant @@ OAuthRateLimitLastRefilledAtTag

  trait OAuthRateLimitExpiresAtTag
  type OAuthRateLimitExpiresAt = Instant @@ OAuthRateLimitExpiresAtTag
}

object OAuthDomain extends Domain {
  sealed abstract class OAuthScope(val value: String)
  object OAuthScope {
    case object OrganizationRead extends OAuthScope("organization:read")
    case object UsersRead        extends OAuthScope("users:read")
    case object ClientsRead      extends OAuthScope("clients:read")
    case object ProjectsRead     extends OAuthScope("projects:read")
    case object WorkUnitsRead    extends OAuthScope("work-units:read")
    case object WorkUnitsWrite   extends OAuthScope("work-units:write")
    case object ReportsRead      extends OAuthScope("reports:read")
    case object ReportsExport    extends OAuthScope("reports:export")

    val values: Vector[OAuthScope] =
      Vector(OrganizationRead, UsersRead, ClientsRead, ProjectsRead, WorkUnitsRead, WorkUnitsWrite, ReportsRead, ReportsExport)

    private val byValue: Map[String, OAuthScope] = values.map(scope => scope.value -> scope).toMap

    def fromValue(value: String): Option[OAuthScope] = byValue.get(value)

    implicit val encoder: Encoder[OAuthScope] = Encoder.encodeString.contramap(_.value)
    implicit val decoder: Decoder[OAuthScope] = Decoder.decodeString.emap(value => fromValue(value).toRight("Unknown OAuth scope"))
  }

  sealed trait OAuthScopeError
  object OAuthScopeError {
    case object EmptyGrantedScopes extends OAuthScopeError
    case object InvalidScope       extends OAuthScopeError
  }

  sealed trait OAuthProjectAccess
  object OAuthProjectAccess {
    case object AllProjects                                       extends OAuthProjectAccess
    final case class SelectedProjects(projectIds: Set[ProjectId]) extends OAuthProjectAccess

    implicit val encoder: Encoder[OAuthProjectAccess] = Encoder.instance {
      case AllProjects => Json.obj("mode" -> Json.fromString(OAuthProjectAccessMode.AllProjects.entryName))
      case SelectedProjects(projectIds) =>
        Json.obj(
          "mode"       -> Json.fromString(OAuthProjectAccessMode.SelectedProjects.entryName),
          "projectIds" -> Json.arr(projectIds.toSeq.sortBy(_.toString).map(id => Json.fromString(id.toString)): _*)
        )
    }

    implicit val decoder: Decoder[OAuthProjectAccess] = Decoder.instance { cursor =>
      cursor.get[String]("mode").flatMap {
        case "AllProjects" =>
          cursor.get[Option[Set[String]]]("projectIds").flatMap {
            case None    => Right(AllProjects)
            case Some(_) => Left(io.circe.DecodingFailure("AllProjects rejects projectIds", cursor.history))
          }
        case "SelectedProjects" =>
          cursor.get[Set[String]]("projectIds").flatMap { rawIds =>
            val parsed = rawIds.toVector.map(raw => Try(UUID.fromString(raw)).toOption.filter(_.toString == raw).map(ProjectId.apply))
            Either.cond(
              rawIds.nonEmpty && parsed.forall(_.nonEmpty),
              SelectedProjects(parsed.flatten.toSet),
              io.circe.DecodingFailure("SelectedProjects requires canonical projectIds", cursor.history)
            )
          }
        case _ => Left(io.circe.DecodingFailure("Unknown OAuth project access mode", cursor.history))
      }
    }
  }

  sealed trait OAuthProjectPolicyError
  object OAuthProjectPolicyError {
    case object AllProjectsMustNotHaveProjectIds extends OAuthProjectPolicyError
    case object SelectedProjectsMustNotBeEmpty   extends OAuthProjectPolicyError
  }

  final case class OAuthClientCredentials(clientId: OAuthClientPublicId, clientSecret: OAuthClientSecret) {
    override def toString: String = s"OAuthClientCredentials(clientId=$clientId,clientSecret=<redacted>)"
  }

  final case class OAuthTokenClaims(
    issuer: String,
    audience: String,
    subject: UserId,
    clientId: OAuthClientPublicId,
    organizationId: OrganizationId,
    scopes: Set[OAuthScope],
    policyVersion: Long,
    tokenId: UUID,
    issuedAt: Instant,
    expiresAt: Instant)

  sealed trait OAuthJwtError
  object OAuthJwtError {
    case object InvalidToken extends OAuthJwtError
    case object UnknownKey   extends OAuthJwtError
    case object Expired      extends OAuthJwtError
    case object NotYetValid  extends OAuthJwtError
  }

  sealed trait OAuthClientStatus extends EnumEntry
  object OAuthClientStatus extends Enum[OAuthClientStatus] {
    case object Active  extends OAuthClientStatus
    case object Revoked extends OAuthClientStatus

    override val values: IndexedSeq[OAuthClientStatus] = findValues
  }

  sealed trait OAuthClientEffectiveStatus extends EnumEntry
  object OAuthClientEffectiveStatus extends Enum[OAuthClientEffectiveStatus] {
    case object Active  extends OAuthClientEffectiveStatus
    case object Expired extends OAuthClientEffectiveStatus
    case object Revoked extends OAuthClientEffectiveStatus

    override val values: IndexedSeq[OAuthClientEffectiveStatus] = findValues

    def resolve(
      persistedStatus: OAuthClientStatus,
      expiresAt: Option[OAuthClientExpiresAt],
      now: Instant
    ): OAuthClientEffectiveStatus =
      persistedStatus match {
        case OAuthClientStatus.Revoked                                                     => Revoked
        case OAuthClientStatus.Active if expiresAt.exists(expiry => !now.isBefore(expiry)) => Expired
        case OAuthClientStatus.Active                                                      => Active
      }
  }

  sealed trait OAuthProjectAccessMode extends EnumEntry
  object OAuthProjectAccessMode extends Enum[OAuthProjectAccessMode] {
    case object AllProjects      extends OAuthProjectAccessMode
    case object SelectedProjects extends OAuthProjectAccessMode

    override val values: IndexedSeq[OAuthProjectAccessMode] = findValues
  }

  sealed trait OAuthAuditCategory extends EnumEntry
  object OAuthAuditCategory extends Enum[OAuthAuditCategory] {
    case object Management     extends OAuthAuditCategory
    case object Authentication extends OAuthAuditCategory

    override val values: IndexedSeq[OAuthAuditCategory] = findValues
  }

  sealed trait OAuthAuditActorType extends EnumEntry
  object OAuthAuditActorType extends Enum[OAuthAuditActorType] {
    case object Human          extends OAuthAuditActorType
    case object ServiceAccount extends OAuthAuditActorType
    case object Anonymous      extends OAuthAuditActorType
    case object System         extends OAuthAuditActorType

    override val values: IndexedSeq[OAuthAuditActorType] = findValues
  }

  sealed trait OAuthAuditOutcome extends EnumEntry
  object OAuthAuditOutcome extends Enum[OAuthAuditOutcome] {
    case object Success extends OAuthAuditOutcome
    case object Failure extends OAuthAuditOutcome

    override val values: IndexedSeq[OAuthAuditOutcome] = findValues
  }

  sealed trait OAuthAuditEventType extends EnumEntry
  object OAuthAuditEventType extends Enum[OAuthAuditEventType] {
    case object Create        extends OAuthAuditEventType
    case object Update        extends OAuthAuditEventType
    case object Rotate        extends OAuthAuditEventType
    case object Revoke        extends OAuthAuditEventType
    case object TokenIssued   extends OAuthAuditEventType
    case object InvalidClient extends OAuthAuditEventType
    case object InvalidScope  extends OAuthAuditEventType
    case object RateLimit     extends OAuthAuditEventType

    override val values: IndexedSeq[OAuthAuditEventType] = findValues
  }

  sealed trait OAuthAuditFailureCategory extends EnumEntry with EnumEntry.Hyphencase
  object OAuthAuditFailureCategory extends Enum[OAuthAuditFailureCategory] {
    case object MalformedCredentials extends OAuthAuditFailureCategory
    case object UnknownClient        extends OAuthAuditFailureCategory
    case object InvalidSecret        extends OAuthAuditFailureCategory
    case object RevokedClient        extends OAuthAuditFailureCategory
    case object ExpiredClient        extends OAuthAuditFailureCategory
    case object MalformedScope       extends OAuthAuditFailureCategory
    case object UnknownScope         extends OAuthAuditFailureCategory
    case object UngrantedScope       extends OAuthAuditFailureCategory
    case object IpRateLimit          extends OAuthAuditFailureCategory
    case object ClientRateLimit      extends OAuthAuditFailureCategory

    override val values: IndexedSeq[OAuthAuditFailureCategory] = findValues
  }

  sealed trait OAuthRateLimitDimension extends EnumEntry
  object OAuthRateLimitDimension extends Enum[OAuthRateLimitDimension] {
    case object Ip            extends OAuthRateLimitDimension
    case object ClientFailure extends OAuthRateLimitDimension

    override val values: IndexedSeq[OAuthRateLimitDimension] = findValues
  }
}
