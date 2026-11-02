package pl.iterators.epoca.workunit.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait WorkUnitIdTag
  type WorkUnitId = UUID @@ WorkUnitIdTag

  trait WorkUnitDateTag
  type WorkUnitDate = LocalDate @@ WorkUnitDateTag

  trait WorkUnitDurationTag
  type WorkUnitDuration = Duration @@ WorkUnitDurationTag

  trait WorkUnitTaskIdTag
  type WorkUnitTaskId = String @@ WorkUnitTaskIdTag

  trait WorkUnitDescriptionTag
  type WorkUnitDescription = String @@ WorkUnitDescriptionTag

  trait WorkUnitCreatedAtTag
  type WorkUnitCreatedAt = Instant @@ WorkUnitCreatedAtTag

  trait WorkUnitModifiedAtTag
  type WorkUnitModifiedAt = Instant @@ WorkUnitModifiedAtTag

  trait WorkUnitArchivedAtTag
  type WorkUnitArchivedAt = Instant @@ WorkUnitArchivedAtTag
}

object WorkUnitDomain extends Domain {
  sealed trait WorkUnitEntryType extends EnumEntry
  object WorkUnitEntryType extends Enum[WorkUnitEntryType] {
    final case object Worklog extends WorkUnitEntryType
    final case object Note    extends WorkUnitEntryType
    override def values: immutable.IndexedSeq[WorkUnitEntryType] = findValues
  }

  sealed trait WorkUnitState extends EnumEntry
  object WorkUnitState extends Enum[WorkUnitState] {
    final case object Active         extends WorkUnitState
    final case object Archived       extends WorkUnitState
    final case object Deleted        extends WorkUnitState
    final case object Draft          extends WorkUnitState
    final case object GdprAnonymized extends WorkUnitState
    final case object Inactive       extends WorkUnitState
    override def values: immutable.IndexedSeq[WorkUnitState] = findValues
    val liveStates: Seq[WorkUnitState]                       = Seq(Active, Inactive)
    val editableStates: Seq[WorkUnitState]                   = Seq(Active, Draft)
  }
}
