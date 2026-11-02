package pl.iterators.epoca.workunit.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration, WorkUnitEntryType}
import pl.iterators.epoca.workunit.domain.WorkUnitEntryPolicy._

import java.time.{Duration, LocalDate}

class WorkUnitEntryPolicySpec extends SpecificationLike {
  private val businessToday = LocalDate.parse("2026-07-29")
  private val today         = WorkUnitDate(businessToday)
  private val yesterday     = WorkUnitDate(LocalDate.parse("2026-07-28"))
  private val tomorrow      = WorkUnitDate(LocalDate.parse("2026-07-30"))

  "normalizeDuration" should {
    "require a duration for worklogs" in {
      WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Worklog, None) shouldEqual Left(DurationRequired)
    }

    "reject worklog durations whose whole-second value is not positive" in {
      val rejected = Seq(Duration.ZERO, Duration.ofSeconds(-1L), Duration.ofMillis(-500L), Duration.ofMillis(999L)).map(WorkUnitDuration.apply)

      rejected.map(duration => WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Worklog, Some(duration))) shouldEqual
        rejected.map(_ => Left(NonPositiveWorklogDuration))
    }

    "preserve worklog durations of exactly one second or more" in {
      val accepted = Seq(Duration.ofSeconds(1L), Duration.ofMillis(1500L)).map(WorkUnitDuration.apply)

      accepted.map(duration => WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Worklog, Some(duration))) shouldEqual
        accepted.map(Right.apply)
    }

    "normalize an omitted note duration to zero" in {
      WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Note, None) shouldEqual Right(WorkUnitDuration(Duration.ZERO))
    }

    "preserve an explicit zero note duration" in {
      val zero = WorkUnitDuration(Duration.ZERO)

      WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Note, Some(zero)) shouldEqual Right(zero)
    }

    "reject every non-zero note duration, including subsecond values" in {
      val rejected =
        Seq(Duration.ofSeconds(1L), Duration.ofSeconds(-1L), Duration.ofMillis(500L), Duration.ofMillis(-500L)).map(WorkUnitDuration.apply)

      rejected.map(duration => WorkUnitEntryPolicy.normalizeDuration(WorkUnitEntryType.Note, Some(duration))) shouldEqual
        rejected.map(_ => Left(NonNeutralNoteDuration))
    }
  }

  "validateDate" should {
    "accept yesterday and today for worklogs" in {
      Seq(yesterday, today).map(WorkUnitEntryPolicy.validateDate(WorkUnitEntryType.Worklog, _, businessToday)) shouldEqual Seq(Right(()), Right(()))
    }

    "reject a future worklog date" in {
      WorkUnitEntryPolicy.validateDate(WorkUnitEntryType.Worklog, tomorrow, businessToday) shouldEqual Left(FutureWorklogDate)
    }

    "accept past, present, and arbitrarily future note dates" in {
      val distantFuture = WorkUnitDate(LocalDate.parse("2126-07-29"))

      Seq(yesterday, today, tomorrow, distantFuture).map(WorkUnitEntryPolicy.validateDate(WorkUnitEntryType.Note, _, businessToday)) shouldEqual
        Seq.fill(4)(Right(()))
    }
  }
}
