package pl.iterators.epoca.organization.domain

import enumeratum.{Enum, EnumEntry}
import pl.iterators.kebs.tag.meta.tagged
import pl.iterators.kebs.tagged._

import java.time.Instant
import java.util.UUID
import scala.collection.immutable

@tagged trait Domain {
  trait OrganizationIdTag
  type OrganizationId = UUID @@ OrganizationIdTag

  trait OrganizationNameTag
  type OrganizationName = String @@ OrganizationNameTag

  object OrganizationName {
    def validate(name: String): Either[String, String] = if (name.trim.isEmpty) Left("Organization name cannot be empty") else Right(name.trim)
  }

  trait OrganizationSlugTag
  type OrganizationSlug = String @@ OrganizationSlugTag

  trait OrganizationCreatedAtTag
  type OrganizationCreatedAt = Instant @@ OrganizationCreatedAtTag

  trait OrganizationModifiedAtTag
  type OrganizationModifiedAt = Instant @@ OrganizationModifiedAtTag

  trait OrganizationArchivedAtTag
  type OrganizationArchivedAt = Instant @@ OrganizationArchivedAtTag

  trait OrganizationUserIdTag
  type OrganizationUserId = UUID @@ OrganizationUserIdTag
  object OrganizationUserId {
    def random: OrganizationUserId = OrganizationUserId(UUID.randomUUID())
  }

  trait OrganizationUserArchivedAtTag
  type OrganizationUserArchivedAt = Instant @@ OrganizationUserArchivedAtTag
}

object OrganizationDomain extends Domain {
  sealed trait OrganizationUserRole extends EnumEntry

  object OrganizationUserRole extends Enum[OrganizationUserRole] {
    final case object Owner extends OrganizationUserRole

    final case object Manager extends OrganizationUserRole

    final case object External extends OrganizationUserRole

    final case object Employee extends OrganizationUserRole

    override def values: immutable.IndexedSeq[OrganizationUserRole] = findValues

    val managementAccessRoles: Seq[OrganizationUserRole] = Seq(OrganizationUserRole.Owner, OrganizationUserRole.Manager)
  }
}
