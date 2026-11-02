package pl.iterators.epoca.stats.repositories

import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableKey
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{FieldValue, GlobalFilters, PathSegment, Request}
import slick.dbio.DBIO

trait WorkUnitPivotQueryRepository {
  import WorkUnitPivotQueryRepository._

  def coordinateCounts(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[CoordinateCounts]
  def aggregate(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[Seq[AggregateRow]]
  def availableFilterMembers(
    organizationId: OrganizationId,
    request: Request,
    filters: GlobalFilters
  ): DBIO[Map[WorkUnitPivotTableKey, Seq[FieldValue]]]
}

object WorkUnitPivotQueryRepository {
  final case class CoordinateCounts(row: Long, column: Long)
  final case class AggregateRow(
    rowPath: Seq[PathSegment],
    columnPath: Seq[PathSegment],
    entriesCount: Long,
    durationSumNanos: BigInt,
    durationAvgNanos: Option[BigInt])
}
