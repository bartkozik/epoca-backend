package pl.iterators.epoca.invitation.services

import io.circe.Json
import pl.iterators.epoca.invitation.domain.InvitationOnboarding._
import pl.iterators.epoca.invitation.domain.InvitationPolicy
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityValue}
import pl.iterators.epoca.utils.db.EmailCanonicalization

import java.time.{Duration, Instant}
import scala.util.Try

object InvitationManagementJson {
  private def field(json: Json, name: String): Either[String, Json]    = json.asObject.flatMap(_(name)).toRight(s"$name is required")
  private def string(json: Json, name: String): Either[String, String] = field(json, name).flatMap(_.asString.toRight(s"$name must be a string"))
  private def strict(
    json: Json,
    keys: Set[String],
    path: String
  ): Either[String, Unit] = json.asObject match {
    case None      => Left(s"$path must be an object")
    case Some(obj) => obj.keys.find(key => !keys(key)).map(key => Left(s"$path.$key is not allowed")).getOrElse(Right(()))
  }
  private def name(json: Json, key: String): Either[String, String] = string(json, key).map(_.trim).flatMap { text =>
    Either.cond(text.nonEmpty && text.length <= 200 && !text.exists(_ < ' '), text, s"$key must be between 1 and 200 characters")
  }
  def revision(json: Json): Either[String, Long] = string(json, "expectedRevision").flatMap { text =>
    Either
      .cond(text.matches("(?:0|[1-9][0-9]*)"), text, "expectedRevision must be a decimal digit string")
      .flatMap(value => Try(value.toLong).toEither.left.map(_ => "expectedRevision is out of range"))
  }
  def decodeCreate(json: Json): Either[String, InvitationCreateBody] = for {
    _       <- strict(json, Set("firstName", "lastName", "email", "role", "locale"), "request")
    first   <- name(json, "firstName")
    last    <- name(json, "lastName")
    address <- string(json, "email").map(EmailCanonicalization.canonical)
    _ <- Either.cond(
           address.length <= 320 && UserIdentityValue.isValid(IdentityType.MailAddress, UserIdentityValue(address)),
           (),
           "email must be a valid email address"
         )
    roleText <- string(json, "role")
    role <- Seq(OrganizationUserRole.Owner, OrganizationUserRole.Manager, OrganizationUserRole.Employee, OrganizationUserRole.External)
              .find(_.toString == roleText)
              .toRight("role must be Owner, Manager, Employee or External")
    locale <- string(json, "locale")
    _      <- Either.cond(Set("pl", "en")(locale), (), "locale must be pl or en")
  } yield InvitationCreateBody(first, last, address, role, locale)

  def decodePatch(record: Record, json: Json): Either[String, (Long, InvitationCreateBody)] = for {
    _        <- strict(json, Set("expectedRevision", "firstName", "lastName", "role", "locale"), "request")
    expected <- revision(json)
    current = Json.obj(
                "firstName" -> record.firstName.fold[Json](Json.Null)(Json.fromString),
                "lastName"  -> record.lastName.fold[Json](Json.Null)(Json.fromString),
                "email"     -> Json.fromString(record.canonicalEmail.getOrElse(record.invitation.identityValue)),
                "role"      -> (record.invitation.roles match { case Seq(role) => Json.fromString(role.toString); case _ => Json.Null }),
                "locale"    -> record.locale.fold[Json](Json.Null)(Json.fromString)
              )
    merged = json.asObject.get.remove("expectedRevision").toIterable.foldLeft(current.asObject.get) { case (fields, (key, value)) =>
               fields.add(key, value)
             }
    decoded <- decodeCreate(Json.fromJsonObject(merged))
  } yield (expected, decoded)

  def encodeCreate(body: InvitationCreateBody): Json = Json.obj(
    "firstName" -> Json.fromString(body.firstName),
    "lastName"  -> Json.fromString(body.lastName),
    "email"     -> Json.fromString(body.email),
    "role"      -> Json.fromString(body.role.toString),
    "locale"    -> Json.fromString(body.locale)
  )

  def status(record: Record, now: Instant): String = {
    val i = record.invitation
    if (i.acceptAt.isDefined) "Accepted"
    else if (i.archivedAt.isDefined || i.rejectAt.isDefined) "Cancelled"
    else if (i.expireAt.exists(!now.isBefore(_))) "Expired"
    else "Pending"
  }
  def retryAfter(started: Instant, now: Instant): Long =
    math.max(0L, (Duration.between(now, InvitationPolicy.nextAllowedSend(started)).toMillis + 999L) / 1000L)
  def admin(
    record: Record,
    attempts: Vector[DeliveryAttempt],
    now: Instant
  ): Json = {
    val i    = record.invitation
    val last = attempts.filter(_.generation == record.generation).sortBy(_.startedAt).lastOption
    val deliveryStatus = last.fold("NotSent") { a =>
      a.result match {
        case Some(DeliveryResult.Succeeded)                                           => "Sent"
        case Some(DeliveryResult.Failed)                                              => "Failed"
        case Some(DeliveryResult.Unknown)                                             => "Unknown"
        case None if InvitationPolicy.deliveryUnknown(now, a.startedAt, a.finishedAt) => "Unknown"
        case None                                                                     => "Started"
      }
    }
    val delivery = Json.obj(
      "status"            -> Json.fromString(deliveryStatus),
      "lastAttemptAt"     -> last.fold[Json](Json.Null)(a => Json.fromString(a.startedAt.toString)),
      "retryAfterSeconds" -> Json.fromLong(last.map(a => retryAfter(a.startedAt, now)).getOrElse(0L))
    )
    val dto = InvitationAdminDto(
      i.id,
      i.identityValue,
      record.firstName,
      record.lastName,
      i.roles.map(_.toString),
      record.protocolVersion,
      record.revision.toString,
      status(record, now),
      i.expireAt.map(v => v: Instant),
      delivery,
      i.createdAt,
      i.modifiedAt
    )
    def optional(value: Option[String]): Json = value.fold[Json](Json.Null)(Json.fromString)
    Json.obj(
      "id"              -> Json.fromString(dto.id.toString),
      "email"           -> Json.fromString(dto.email),
      "firstName"       -> optional(dto.firstName),
      "lastName"        -> optional(dto.lastName),
      "roles"           -> Json.fromValues(dto.roles.map(Json.fromString)),
      "protocolVersion" -> Json.fromInt(dto.protocolVersion),
      "revision"        -> Json.fromString(dto.revision),
      "status"          -> Json.fromString(dto.status),
      "expiresAt"       -> optional(dto.expiresAt.map(_.toString)),
      "delivery"        -> dto.delivery,
      "createdAt"       -> Json.fromString(dto.createdAt.toString),
      "modifiedAt"      -> Json.fromString(dto.modifiedAt.toString)
    )
  }
}
