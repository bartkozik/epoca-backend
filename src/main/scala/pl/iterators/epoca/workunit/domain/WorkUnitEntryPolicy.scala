package pl.iterators.epoca.workunit.domain

import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration, WorkUnitEntryType}

import java.time.{Duration, LocalDate}

object WorkUnitEntryPolicy {
  sealed trait Error
  final case object DurationRequired           extends Error
  final case object NonPositiveWorklogDuration extends Error
  final case object NonNeutralNoteDuration     extends Error
  final case object FutureWorklogDate          extends Error

  def normalizeDuration(entryType: WorkUnitEntryType, duration: Option[WorkUnitDuration]): Either[Error, WorkUnitDuration] =
    entryType match {
      case WorkUnitEntryType.Worklog =>
        duration match {
          case None                                => Left(DurationRequired)
          case Some(value) if value.toSeconds <= 0 => Left(NonPositiveWorklogDuration)
          case Some(value)                         => Right(value)
        }
      case WorkUnitEntryType.Note =>
        duration match {
          case None                                  => Right(WorkUnitDuration(Duration.ZERO))
          case Some(value) if value == Duration.ZERO => Right(value)
          case Some(_)                               => Left(NonNeutralNoteDuration)
        }
    }

  def validateDate(
    entryType: WorkUnitEntryType,
    date: WorkUnitDate,
    businessToday: LocalDate
  ): Either[Error, Unit] =
    entryType match {
      case WorkUnitEntryType.Worklog if date.isAfter(businessToday) => Left(FutureWorklogDate)
      case _                                                        => Right(())
    }
}
