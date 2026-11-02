package pl.iterators.epoca.workunit.services

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.specs2.mutable.SpecificationLike

import java.time.{Clock, DateTimeException, Instant, LocalDate, ZoneId, ZoneOffset}

class WorkUnitBusinessClockSpec extends SpecificationLike {
  private val warsaw = ZoneId.of("Europe/Warsaw")

  private def businessDate(instant: String, zoneId: ZoneId): LocalDate =
    WorkUnitBusinessClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), zoneId).businessToday

  "businessToday" should {
    "use the configured Warsaw date instead of the UTC date" in {
      val instant = "2026-07-29T22:30:00Z"

      businessDate(instant, warsaw) shouldEqual LocalDate.parse("2026-07-30")
      businessDate(instant, ZoneOffset.UTC) shouldEqual LocalDate.parse("2026-07-29")
    }

    "remain date-correct immediately before and after the spring DST transition" in {
      val before = Instant.parse("2026-03-29T00:59:59Z")
      val after  = Instant.parse("2026-03-29T01:00:00Z")

      businessDate(before.toString, warsaw) shouldEqual before.atZone(warsaw).toLocalDate
      businessDate(before.toString, warsaw) shouldEqual LocalDate.parse("2026-03-29")
      businessDate(after.toString, warsaw) shouldEqual after.atZone(warsaw).toLocalDate
      businessDate(after.toString, warsaw) shouldEqual LocalDate.parse("2026-03-29")
    }

    "remain date-correct immediately before and after the autumn DST transition" in {
      val before = Instant.parse("2026-10-25T00:59:59Z")
      val after  = Instant.parse("2026-10-25T01:00:00Z")

      businessDate(before.toString, warsaw) shouldEqual before.atZone(warsaw).toLocalDate
      businessDate(before.toString, warsaw) shouldEqual LocalDate.parse("2026-10-25")
      businessDate(after.toString, warsaw) shouldEqual after.atZone(warsaw).toLocalDate
      businessDate(after.toString, warsaw) shouldEqual LocalDate.parse("2026-10-25")
    }
  }

  "configuration" should {
    "load Europe/Warsaw by default and use a UTC system clock" in {
      val config = ConfigFactory
        .parseResources("application.conf")
        .withOnlyPath("workUnit.businessZone")
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
      val result = WorkUnitBusinessClock(config)

      result.zoneId shouldEqual warsaw
      result.clock.getZone shouldEqual ZoneOffset.UTC
    }

    "honor an explicit business zone override" in {
      val config = ConfigFactory.parseString("workUnit.businessZone = UTC")

      WorkUnitBusinessClock(config).zoneId shouldEqual ZoneId.of("UTC")
    }

    "fail predictably for an invalid business zone" in {
      val config = ConfigFactory.parseString("workUnit.businessZone = invalid/business-zone")

      WorkUnitBusinessClock(config) must throwA[DateTimeException]
    }
  }
}
