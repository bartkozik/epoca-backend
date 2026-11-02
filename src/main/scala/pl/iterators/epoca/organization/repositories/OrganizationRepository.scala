package pl.iterators.epoca.organization.repositories

import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import slick.dbio.DBIO

trait OrganizationRepository {
  import OrganizationRepository._

  def insert(organization: Organization): DBIO[Organization]
  def update(organization: Organization): DBIO[Option[Organization]]
  def find(filter: OrganizationFilters): DBIO[Option[Organization]]
  def list(filter: OrganizationFilters): DBIO[Seq[Organization]]
  def count(filter: OrganizationFilters): DBIO[Int]
}

object OrganizationRepository {

  case class OrganizationFilters(
    id: Option[OrganizationId] = None,
    ids: Option[Seq[OrganizationId]] = None,
    userId: Option[UserId] = None,
    creatorId: Option[UserId] = None,
    slug: Option[OrganizationSlug] = None,
    includeArchived: Boolean = false,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None,
    sortBy: Option[OrganizationSortBy] = None,
    forUpdate: Boolean = false)
      extends FilterParamType

  sealed trait OrganizationSortBy extends EnumEntry

  object OrganizationSortBy extends Enum[OrganizationSortBy] {
    case object OrganizationNameAsc  extends OrganizationSortBy
    case object OrganizationNameDesc extends OrganizationSortBy
    case object CreatedAtAsc         extends OrganizationSortBy
    case object CreatedAtDesc        extends OrganizationSortBy

    override def values: IndexedSeq[OrganizationSortBy] = findValues
    def default: OrganizationSortBy                     = OrganizationSortBy.CreatedAtAsc
  }

}
