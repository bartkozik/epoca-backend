package pl.iterators.epoca.`export`.domain

import enumeratum.{Enum, EnumEntry}
import io.circe.Encoder
import pl.iterators.epoca.stats.domain.StatsDomain.{WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{FieldValue, InvalidConfigurationError}
import pl.iterators.epoca.utils.json.JsonProtocol._

import java.time.LocalDate
import scala.collection.immutable

object WorkUnitPivotExport {
  final case class MemberLabel(
    key: WorkUnitPivotTableKey,
    value: FieldValue,
    label: String)
  final case class Presentation(
    fieldLabels: Map[WorkUnitPivotTableKey, String],
    measureLabels: Map[WorkUnitPivotTableValue, String],
    subtotalLabel: String,
    grandTotalLabel: String,
    noClientLabel: String,
    noTaskLabel: String,
    memberLabels: Seq[MemberLabel])
  final case class Projection(effectiveDateTo: LocalDate)
  final case class ValidatedRequest(
    query: WorkUnitPivotQuery.Request,
    projection: Option[Projection],
    presentation: Presentation)
  object ValidatedRequest {
    def apply(query: WorkUnitPivotQuery.Request, presentation: Presentation): ValidatedRequest =
      new ValidatedRequest(query, None, presentation)
  }

  sealed trait ExportViolationCode extends EnumEntry
  object ExportViolationCode extends Enum[ExportViolationCode] {
    case object MISSING_REQUIRED_PROPERTY     extends ExportViolationCode
    case object INVALID_PROPERTY_TYPE         extends ExportViolationCode
    case object UNSUPPORTED_FIELD             extends ExportViolationCode
    case object INVALID_PROJECTION_VALUE      extends ExportViolationCode
    case object INVALID_PRESENTATION_VALUE    extends ExportViolationCode
    case object DUPLICATE_PRESENTATION_MEMBER extends ExportViolationCode
    case object PRESENTATION_LIMIT_EXCEEDED   extends ExportViolationCode
    override val values: immutable.IndexedSeq[ExportViolationCode] = findValues
  }

  final case class ExportViolation(path: String, code: ExportViolationCode)
  final case class InvalidExportRequest(
    code: String = "PIVOT_INVALID_EXPORT_REQUEST",
    message: String = "Invalid pivot export request",
    violations: Seq[ExportViolation])

  sealed trait ValidationError
  object ValidationError {
    final case class InvalidQuery(error: InvalidConfigurationError)   extends ValidationError
    final case class InvalidPresentation(error: InvalidExportRequest) extends ValidationError
    case object MalformedEnvelope                                     extends ValidationError
  }

  object Limits {
    // Whenever this changes (e.g. adding a new pivot dimension), canonicalBodyBudget(the real
    // stats.pivot.maxDisplayValueCellCount from application.conf) must stay <= requestEntityMaxBytes,
    // or ExportModule.PivotConfig's require() crashes application wiring at startup. Re-check
    // both this value and stats.pivot.maxDisplayValueCellCount together; see
    // ExportModule.PivotConfig and the "load the application defaults..." spec in
    // PostPivotTableWorkUnitExportRouteSpec for the guarded invariant.
    final val supportedDimensionCount: Int      = 8
    final val maxLabelCodePoints: Int           = 140
    final val maxLabelUtf8Bytes: Int            = 560
    final val maxMemberEntryBytes: Long         = 768L
    final val fixedEnvelopeAllowanceBytes: Long = 8_000_000L
    final val requestEntityMaxBytes: Long       = 64L * 1024L * 1024L

    def memberLabelLimit(maxDisplayValueCellCount: Long): Long =
      saturatingMultiply(maxDisplayValueCellCount.max(0L), supportedDimensionCount.toLong)

    def canonicalBodyBudget(maxDisplayValueCellCount: Long): Long =
      saturatingAdd(saturatingMultiply(memberLabelLimit(maxDisplayValueCellCount), maxMemberEntryBytes), fixedEnvelopeAllowanceBytes)

    private def saturatingMultiply(left: Long, right: Long): Long =
      (BigInt(left) * BigInt(right)).min(BigInt(Long.MaxValue)).toLong

    private def saturatingAdd(left: Long, right: Long): Long =
      (BigInt(left) + BigInt(right)).min(BigInt(Long.MaxValue)).toLong
  }

  implicit lazy val exportViolationEncoder: Encoder[ExportViolation] =
    Encoder.forProduct2("path", "code")(violation => (violation.path, violation.code))
  implicit lazy val invalidExportRequestEncoder: Encoder[InvalidExportRequest] =
    Encoder.forProduct3("code", "message", "violations")(error => (error.code, error.message, error.violations))
}
