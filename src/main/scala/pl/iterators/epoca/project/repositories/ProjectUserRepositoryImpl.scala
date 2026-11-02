package pl.iterators.epoca.project.repositories

import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectUserArchivedAt, ProjectUserId}
import pl.iterators.epoca.project.domain.ProjectUser
import pl.iterators.epoca.project.repositories.ProjectUserRepository.{ProjectUserFilters, ProjectUserNotPermitted}
import pl.iterators.epoca.project.repositories.tables.ProjectUserTable
import pl.iterators.epoca.user.domain.UserDomain.{UserAccountType, UserId}
import pl.iterators.epoca.user.repositories.tables.UserTable
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

class ProjectUserRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[ProjectUser, ProjectUserId, ProjectUserTable]
    with FilteringRepository[ProjectUser, ProjectUserTable, ProjectUserFilters]
    with ProjectUserRepository {

  override protected def ec: ExecutionContext                = e
  protected val table: TableQuery[ProjectUserTable]          = TableQuery[ProjectUserTable]
  private val userTable: TableQuery[UserTable]               = TableQuery[UserTable]
  override protected def getId: ProjectUser => ProjectUserId = _.id
  override protected def idBaseTypedType: BaseTypedType[ProjectUserId] =
    implicitly[BaseTypedType[ProjectUserId]]

  override def insert(projectUser: ProjectUser): DBIO[ProjectUser] =
    validateHumanUsers(Seq(projectUser.userId)).flatMap(_ => (table += projectUser).map(_ => projectUser))

  override def insert(projectUsers: Seq[ProjectUser]): DBIO[Seq[ProjectUser]] =
    validateHumanUsers(projectUsers.map(_.userId)).flatMap(_ => (table ++= projectUsers).map(_ => projectUsers))

  override def bulkArchiveAt(projectUserIds: Seq[ProjectUserId], at: ProjectUserArchivedAt): DBIO[Int] =
    table.filter(_.id inSet projectUserIds).map(_.archivedAt).update(Some(at))

  override def lockOrdered(filter: ProjectUserFilters): DBIO[Seq[ProjectUser]] =
    filtered(table, filter).sortBy(row => (row.projectId, row.userId, row.id)).forUpdate.result

  override protected def prepareFilterQuery(tableQuery: Query[ProjectUserTable, ProjectUser, Seq], params: ProjectUserFilters)
    : Query[ProjectUserTable, ProjectUser, Seq] = {
    val query = filtered(tableQuery, params).sortBy(_.userId)

    if (params.forUpdate) query.forUpdate else query
  }

  private def filtered(tableQuery: Query[ProjectUserTable, ProjectUser, Seq], params: ProjectUserFilters): Query[ProjectUserTable, ProjectUser, Seq] =
    tableQuery
      .filter(_.userId in userTable.filter(_.accountType === LiteralColumn(UserAccountType.Human: UserAccountType)).map(_.id))
      .filterOpt(params.userIds)(_.userId inSet _)
      .filterOpt(params.userId)(_.userId === _)
      .filterOpt(params.projectIds)(_.projectId inSet _)
      .filterOpt(params.role)(_.role === _)
      .filterOpt(params.notDeletedAt)(_.notDeletedAt(_))
      .filterIf(!params.includeArchived)(_.notDeletedNow)

  private def validateHumanUsers(userIds: Seq[UserId]): DBIO[Unit] = {
    val distinctUserIds = userIds.distinct
    userTable
      .filter(user => user.id.inSet(distinctUserIds) && user.accountType === LiteralColumn(UserAccountType.Human: UserAccountType))
      .map(_.id)
      .result
      .flatMap { foundUserIds =>
        if (foundUserIds.toSet == distinctUserIds.toSet) DBIO.successful(())
        else DBIO.failed(ProjectUserNotPermitted)
      }
  }

}
