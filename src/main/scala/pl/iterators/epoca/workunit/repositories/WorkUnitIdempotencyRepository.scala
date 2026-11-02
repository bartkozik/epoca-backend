package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyRecordValidationError.{
  InvalidOperation,
  InvalidOperationTarget,
  InvalidRecordShape,
  InvalidState
}
import pl.iterators.epoca.workunit.idempotency.{WorkUnitIdempotencyRecordValidationError, _}
import slick.dbio.DBIO

import java.time.Instant

trait WorkUnitIdempotencyRepository {
  def claim(claim: NewWorkUnitIdempotencyClaim): DBIO[WorkUnitIdempotencyClaimResult]
  def complete(
    scope: WorkUnitIdempotencyScope,
    outcome: WorkUnitWriteOutcome,
    expiresAt: Instant
  ): DBIO[Int]
  def redactExpired(now: Instant): DBIO[Int]
  def find(scope: WorkUnitIdempotencyScope): DBIO[Option[WorkUnitIdempotencyRecord]]
}

object WorkUnitIdempotencyRepository {
  final class WorkUnitIdempotencyRecordDecodingException private (val validationError: WorkUnitIdempotencyRecordValidationError)
      extends RuntimeException(WorkUnitIdempotencyRecordDecodingException.safeMessage(validationError))

  object WorkUnitIdempotencyRecordDecodingException {
    def apply(validationError: WorkUnitIdempotencyRecordValidationError): WorkUnitIdempotencyRecordDecodingException =
      new WorkUnitIdempotencyRecordDecodingException(validationError)

    private def safeMessage(validationError: WorkUnitIdempotencyRecordValidationError): String = {
      val prefix = "Stored work unit idempotency record failed validation"

      validationError match {
        case _: InvalidOperationTarget => s"$prefix: category=invalid-operation-target"
        case _: InvalidOperation       => s"$prefix: category=invalid-operation"
        case _: InvalidState           => s"$prefix: category=invalid-state"
        case InvalidRecordShape(_, missingFields, unexpectedPresentFields) =>
          s"$prefix: category=invalid-record-shape; missingFields=${renderFields(missingFields)}; " +
            s"unexpectedPresentFields=${renderFields(unexpectedPresentFields)}"
      }
    }

    private def renderFields(fields: Seq[String]): String = fields.sorted.mkString("[", ",", "]")
  }

  final case class WorkUnitIdempotencyCompletionInvariantViolation(updatedRows: Int)
      extends RuntimeException(s"Expected to complete exactly one Processing idempotency record, updated $updatedRows")

  final case class WorkUnitIdempotencyClaimInvariantViolation(reason: String)
      extends RuntimeException(s"Work unit idempotency claim invariant failed: $reason")
}
