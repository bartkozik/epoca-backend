package pl.iterators.epoca.workunit.repositories.tables

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.{BaseTable, PrimaryKeyTable}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.WorkUnitOrder
import slick.lifted.{PrimaryKey, ProvenShape}

class WorkUnitOrderTable(tag: Tag) extends BaseTable[WorkUnitOrder](tag, "work_unit_order") with PrimaryKeyTable[WorkUnitOrder] {

  def organizationId: Rep[OrganizationId] = column[OrganizationId]("organization_id")
  def userId: Rep[UserId]                 = column[UserId]("user_id")
  def date: Rep[WorkUnitDate]             = column[WorkUnitDate]("date")
  def workUnitIds: Rep[Seq[WorkUnitId]]   = column[Seq[WorkUnitId]]("work_unit_ids")

  override val primaryKey: PrimaryKey = primaryKey("work_unit_order_pkey", (organizationId, userId, date))

  override def * : ProvenShape[WorkUnitOrder] =
    (organizationId, userId, date, workUnitIds) <> (WorkUnitOrder.tupled, WorkUnitOrder.unapply _)

}
