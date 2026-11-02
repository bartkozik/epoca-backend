package pl.iterators.epoca.user.repositories

import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.domain.{User, UserWithIdentities}
import pl.iterators.epoca.utils.db.{FilterParamType, PageSizeAndNumber}
import slick.dbio.DBIO

trait UserRepository {
  import UserRepository._

  def insert(user: User): DBIO[User]
  def find(filter: UserFilters): DBIO[Option[User]]
  def list(filter: UserFilters): DBIO[Seq[User]]
  def update(user: User): DBIO[Option[User]]
  def listWithIdentities(filter: UserFilters): DBIO[Seq[UserWithIdentities]]

}

object UserRepository {
  case class UserFilters(
    id: Option[UserId] = None,
    ids: Option[Seq[UserId]] = None,
    email: Option[MailAddress] = None,
    phoneNumber: Option[PhoneNumber] = None,
    pageSizeAndNumber: Option[PageSizeAndNumber] = None,
    sortBy: Option[UserSortBy] = None,
    includeArchived: Boolean = false,
    forUpdate: Boolean = false)
      extends FilterParamType

  sealed trait UserSortBy extends EnumEntry

  object UserSortBy extends Enum[UserSortBy] {
    case object CreatedAtAsc  extends UserSortBy
    case object CreatedAtDesc extends UserSortBy

    override def values: IndexedSeq[UserSortBy] = findValues
    def default: UserSortBy                     = UserSortBy.CreatedAtAsc
  }
}
