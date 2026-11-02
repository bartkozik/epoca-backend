package pl.iterators.epoca.workunit.domain

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitId}

case class WorkUnitOrder(
  organizationId: OrganizationId,
  userId: UserId,
  date: WorkUnitDate,
  workUnitIds: Seq[WorkUnitId])
