package pl.iterators.epoca.client.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.utils.regex.HexColorValidator
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait ClientIdTag
  type ClientId = UUID @@ ClientIdTag

  trait ClientNameTag
  type ClientName = String @@ ClientNameTag

  object ClientName {
    sealed trait Error
    case object Empty extends Error

    def validate(name: String): Either[Empty.type, String] = if (name.trim.isEmpty) Left(Empty) else Right(name.trim)
  }

  trait ClientColorTag
  type ClientColor = String @@ ClientColorTag

  trait ClientTextureTag
  type ClientTexture = String @@ ClientTextureTag

  object ClientColor {
    sealed trait Error
    case object Invalid extends Error

    def validate(color: String): Either[Invalid.type, String] = if (!HexColorValidator.validate(color)) Left(Invalid) else Right(color)
  }

  trait ClientLogoTag
  type ClientLogo = String @@ ClientLogoTag

  trait ClientCreatedAtTag
  type ClientCreatedAt = Instant @@ ClientCreatedAtTag

  trait ClientModifiedAtTag
  type ClientModifiedAt = Instant @@ ClientModifiedAtTag

  trait ClientArchivedAtTag
  type ClientArchivedAt = Instant @@ ClientArchivedAtTag

  trait ClientProjectCountTag
  type ClientProjectCount = Int @@ ClientProjectCountTag
}

object ClientDomain extends Domain {
  sealed trait ClientType extends EnumEntry
  object ClientType extends Enum[ClientType] {
    final case object External extends ClientType
    final case object Internal extends ClientType

    override def values: immutable.IndexedSeq[ClientType] = findValues
  }
}
