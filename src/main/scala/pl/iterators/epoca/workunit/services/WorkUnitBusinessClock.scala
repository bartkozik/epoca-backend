package pl.iterators.epoca.workunit.services

import com.typesafe.config.Config

import java.time.{Clock, Instant, LocalDate, ZoneId}

final case class WorkUnitBusinessClock(clock: Clock, zoneId: ZoneId) {
  def businessToday: LocalDate = Instant.now(clock).atZone(zoneId).toLocalDate
}

object WorkUnitBusinessClock {
  def apply(config: Config): WorkUnitBusinessClock =
    WorkUnitBusinessClock(clock = Clock.systemUTC(), zoneId = ZoneId.of(config.getString("workUnit.businessZone")))
}
