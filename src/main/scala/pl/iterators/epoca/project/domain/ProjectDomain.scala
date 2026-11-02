package pl.iterators.epoca.project.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait ProjectIdTag
  type ProjectId = UUID @@ ProjectIdTag

  trait ProjectNameTag
  type ProjectName = String @@ ProjectNameTag

  trait ProjectDescriptionTag
  type ProjectDescription = String @@ ProjectDescriptionTag

  object ProjectName {
    sealed trait Error
    case object Empty extends Error

    def validate(name: String): Either[Empty.type, String] = if (name.trim.isEmpty) Left(Empty) else Right(name.trim)
  }

  trait ProjectCreatedAtTag
  type ProjectCreatedAt = Instant @@ ProjectCreatedAtTag

  trait ProjectModifiedAtTag
  type ProjectModifiedAt = Instant @@ ProjectModifiedAtTag

  trait ProjectArchivedAtTag
  type ProjectArchivedAt = Instant @@ ProjectArchivedAtTag

  trait ProjectUserIdTag
  type ProjectUserId = UUID @@ ProjectUserIdTag

  trait ProjectUserCreatedAtTag
  type ProjectUserCreatedAt = Instant @@ ProjectUserCreatedAtTag

  trait ProjectUserArchivedAtTag
  type ProjectUserArchivedAt = Instant @@ ProjectUserArchivedAtTag

  trait ProjectUserCountTag
  type ProjectUserCount = Int @@ ProjectUserCountTag
}

object ProjectDomain extends Domain {
  sealed trait ProjectRole extends EnumEntry

  object ProjectRole extends Enum[ProjectRole] {
    final case object Owner extends ProjectRole

    final case object Member extends ProjectRole

    override def values: immutable.IndexedSeq[ProjectRole] = findValues
  }
}
