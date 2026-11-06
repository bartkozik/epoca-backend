package pl.iterators.epoca.user.domain

import enumeratum.EnumEntry.Lowercase
import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.time.Instant
import scala.collection.immutable

final case class UserAvatarSource(
  userId: UserId,
  source: UserAvatarSource.Source,
  sourceUrl: Option[String],
  avatarUrl: String,
  syncedAt: Instant)

object UserAvatarSource {
  sealed trait Source extends EnumEntry with Lowercase
  object Source extends Enum[Source] {
    case object Google extends Source
    case object Manual extends Source

    override val values: immutable.IndexedSeq[Source] = findValues
  }
}
