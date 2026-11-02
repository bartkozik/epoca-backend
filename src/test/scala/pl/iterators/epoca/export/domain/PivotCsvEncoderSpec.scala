package pl.iterators.epoca.`export`.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.export.domain.PivotExportProjection.Value.{Count, DurationMinutes}
import pl.iterators.epoca.export.domain.PivotExportProjection._
import pl.iterators.epoca.stats.domain.StatsDomain.WorkUnitPivotTableValue
import pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.{Coordinate, CoordinateKind}

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

class PivotCsvEncoderSpec extends SpecificationLike {
  "PivotCsvEncoder" should {
    "encode completed Month headers in chronological order with exact blanks and Grand Total last" in {
      val table = Table(
        rowFieldLabels = Vector("Użytkownik"),
        columns = Vector("Luty 2026", "Marzec 2026", "Kwiecień 2026", "Maj 2026", "Czerwiec 2026", "Lipiec 2026", "Sierpień 2026", "Suma końcowa")
          .map(label => column(Vector(label), WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(
          DataRow(RowHeader(Vector(Some("Ala")), CoordinateKind.Leaf), Vector(None, None, None, count(1), count(2), count(3), count(4), count(10)))
        )
      )
      val expected = csvBytes(
        "Użytkownik,Luty 2026 / Liczba wpisów,Marzec 2026 / Liczba wpisów,Kwiecień 2026 / Liczba wpisów," +
          "Maj 2026 / Liczba wpisów,Czerwiec 2026 / Liczba wpisów,Lipiec 2026 / Liczba wpisów," +
          "Sierpień 2026 / Liczba wpisów,Suma końcowa / Liczba wpisów\r\n" +
          "Ala,,,,1,2,3,4,10\r\n"
      )
      val actual = PivotCsvEncoder.encode(table, measureLabels)

      (actual.toSeq shouldEqual expected.toSeq) and
        (actual.take(Utf8Bom.length).toSeq shouldEqual Utf8Bom.toSeq) and
        (new String(actual.drop(Utf8Bom.length), UTF_8).endsWith("\r\n") must beTrue)
    }

    "encode the complete canonical projection as exact deterministic UTF-8 CSV bytes" in {
      val actual   = PivotCsvEncoder.encode(canonicalTable, measureLabels)
      val expected = csvBytes(canonicalCsv)
      val payload  = new String(actual.drop(Utf8Bom.length), UTF_8)

      (actual.toSeq shouldEqual expected.toSeq) and
        (actual.take(Utf8Bom.length).toSeq shouldEqual Utf8Bom.toSeq) and
        (actual.sliding(Utf8Bom.length).count(_.sameElements(Utf8Bom)) shouldEqual 1) and
        (payload.filter(character => character == '\r' || character == '\n') shouldEqual "\r\n" * 5) and
        (payload.endsWith("\r\n") must beTrue) and
        (payload.endsWith("\r\n\r\n") must beFalse) and
        (PivotCsvEncoder.encode(canonicalTable, measureLabels).toSeq shouldEqual actual.toSeq)
    }

    "write the exact same canonical bytes to an output stream" in {
      val output = new ByteArrayOutputStream()

      PivotCsvEncoder.write(canonicalTable, measureLabels, output)

      (output.toByteArray.toSeq shouldEqual PivotCsvEncoder.encode(canonicalTable, measureLabels).toSeq) and
        (output.toByteArray.toSeq shouldEqual csvBytes(canonicalCsv).toSeq)
    }

    "write a large legal table incrementally with field-bounded chunks" in {
      val maximumLabel = "🐈" * 140
      val row          = DataRow(RowHeader(Vector.fill(7)(Some(maximumLabel)), CoordinateKind.Leaf), Vector(Some(Count(1L))))
      val table = Table(
        rowFieldLabels = Vector.tabulate(7)(index => s"Field $index"),
        columns = Vector(column(Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector.fill(2000)(row)
      )
      val output = new CountingOutputStream

      PivotCsvEncoder.write(table, Map(WorkUnitPivotTableValue.EntriesCount -> "Count"), output)

      ((output.totalBytes > 7000000L) should beTrue) and
        ((output.maximumWriteBytes <= maximumLabel.getBytes(UTF_8).length) should beTrue)
    }

    "use a measure label alone when the column coordinate has no labels and format unbounded duration minutes" in {
      val beyondLong = BigInt("9223372036854775808")
      val values     = Vector(BigInt(0), BigInt(5), BigInt(59), BigInt(60), BigInt(1440), BigInt(7530), beyondLong)
      val table = Table(
        rowFieldLabels = Vector.empty,
        columns = Vector.fill(values.size)(column(Vector.empty, WorkUnitPivotTableValue.DurationSum)),
        rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), values.map(value => Some(DurationMinutes(value)))))
      )

      PivotCsvEncoder.encode(table, Map(WorkUnitPivotTableValue.DurationSum -> "Czas")).toSeq shouldEqual
        csvBytes(
          "Czas,Czas,Czas,Czas,Czas,Czas,Czas\r\n" +
            "00:00,00:05,00:59,01:00,24:00,125:30,153722867280912930:08\r\n"
        ).toSeq
    }

    "encode base-10 counts including exact zero and preserve every blank field in the rectangular table" in {
      val table = Table(
        rowFieldLabels = Vector("Level 1", "Level 2", "Level 3"),
        columns = Vector(column(Vector("Group"), WorkUnitPivotTableValue.DurationAvg), column(Vector("Group"), WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(
          DataRow(RowHeader(Vector(Some("Member"), None, Some("Subtotal")), CoordinateKind.Subtotal), Vector(None, Some(Count(0L)))),
          DataRow(RowHeader(Vector(Some("Grand total"), None, None), CoordinateKind.GrandTotal), Vector(None, Some(Count(Long.MaxValue))))
        )
      )
      val labels: Map[WorkUnitPivotTableValue, String] =
        Map(WorkUnitPivotTableValue.DurationAvg -> "Average", WorkUnitPivotTableValue.EntriesCount -> "Count")
      val expected =
        "Level 1,Level 2,Level 3,Group / Average,Group / Count\r\n" +
          "Member,,Subtotal,,0\r\n" +
          "Grand total,,,,9223372036854775807\r\n"
      val actual  = new String(PivotCsvEncoder.encode(table, labels).drop(Utf8Bom.length), UTF_8)
      val records = actual.stripSuffix("\r\n").split("\r\n", -1)

      (actual shouldEqual expected) and
        (records.toSeq.map(_.split(",", -1).length) shouldEqual Seq.fill(records.length)(5))
    }

    "quote commas, double quotes, CR, and LF while preserving direct Unicode" in {
      val table = Table(
        rowFieldLabels = Vector("Wiersz, pole", "Wiersz \"cytat\""),
        columns = Vector(column(Vector("Kolumna\r\nlinia, \"cytat\""), WorkUnitPivotTableValue.EntriesCount)),
        rows =
          Vector(DataRow(RowHeader(Vector(Some("Zażółć\nwiersz, \"cytat\" 🐈"), Some("漢字\rwartość")), CoordinateKind.Leaf), Vector(Some(Count(9L)))))
      )

      PivotCsvEncoder.encode(table, Map(WorkUnitPivotTableValue.EntriesCount -> "Liczba")).toSeq shouldEqual
        csvBytes(
          "\"Wiersz, pole\",\"Wiersz \"\"cytat\"\"\",\"Kolumna\r\nlinia, \"\"cytat\"\" / Liczba\"\r\n" +
            "\"Zażółć\nwiersz, \"\"cytat\"\" 🐈\",\"漢字\rwartość\",9\r\n"
        ).toSeq
    }

    "neutralize every dangerous first non-space prefix in all text field categories before quoting" in {
      val table = Table(
        rowFieldLabels = Vector("=Row", " +Row", "-Row", " @Row", "'Already safe"),
        columns = Vector(column(Vector("=Column"), WorkUnitPivotTableValue.DurationSum), column(Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(
          DataRow(
            RowHeader(Vector(Some("=Member"), Some(" +Subtotal"), Some("-Grand"), Some(" @Member"), Some("'benign")), CoordinateKind.Subtotal),
            Vector(Some(DurationMinutes(BigInt(5))), Some(Count(0L)))
          )
        )
      )
      val labels: Map[WorkUnitPivotTableValue, String] =
        Map(WorkUnitPivotTableValue.DurationSum -> "Duration", WorkUnitPivotTableValue.EntriesCount -> "+Measure")

      PivotCsvEncoder.encode(table, labels).toSeq shouldEqual
        csvBytes(
          "'=Row,' +Row,'-Row,' @Row,'Already safe,'=Column / Duration,'+Measure\r\n" +
            "'=Member,' +Subtotal,'-Grand,' @Member,'benign,00:05,0\r\n"
        ).toSeq
    }

    "fail fast for invalid negative values and non-rectangular rows" in {
      val durationTable = singleValueTable(DurationMinutes(BigInt(-1)), WorkUnitPivotTableValue.DurationSum)
      val countTable    = singleValueTable(Count(-1L), WorkUnitPivotTableValue.EntriesCount)
      val shortHeader = Table(
        rowFieldLabels = Vector("First", "Second"),
        columns = Vector(column(Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("only first")), CoordinateKind.Leaf), Vector(Some(Count(1L)))))
      )
      val shortValues = Table(
        rowFieldLabels = Vector("First"),
        columns = Vector(column(Vector.empty, WorkUnitPivotTableValue.EntriesCount)),
        rows = Vector(DataRow(RowHeader(Vector(Some("first")), CoordinateKind.Leaf), Vector.empty))
      )

      (PivotCsvEncoder.encode(durationTable, measureLabels) must throwA[IllegalArgumentException]) and
        (PivotCsvEncoder.encode(countTable, measureLabels) must throwA[IllegalArgumentException]) and
        (PivotCsvEncoder.encode(shortHeader, measureLabels) must throwA[IllegalArgumentException]) and
        (PivotCsvEncoder.encode(shortValues, measureLabels) must throwA[IllegalArgumentException])
    }
  }

  private val Utf8Bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

  private val emptyCoordinate = Coordinate(CoordinateKind.Leaf, Seq.empty)

  private val measureLabels: Map[WorkUnitPivotTableValue, String] = Map(
    WorkUnitPivotTableValue.DurationSum  -> "Suma czasu",
    WorkUnitPivotTableValue.DurationAvg  -> "Średni czas",
    WorkUnitPivotTableValue.EntriesCount -> "Liczba wpisów"
  )

  private val canonicalColumns = Vector("Lipiec 2026", "Sierpień 2026", "Razem").flatMap { label =>
    Vector(
      column(Vector(label), WorkUnitPivotTableValue.DurationSum),
      column(Vector(label), WorkUnitPivotTableValue.DurationAvg),
      column(Vector(label), WorkUnitPivotTableValue.EntriesCount)
    )
  }

  private val canonicalTable = Table(
    rowFieldLabels = Vector("Person", "Initiative"),
    columns = canonicalColumns,
    rows = Vector(
      row("Użytkownik Żółty", Some("Projekt Żółty"), Seq(120, 120, 1, 180, 180, 1, 300, 150, 2)),
      DataRow(
        RowHeader(Vector(Some("Użytkownik Żółty"), Some("Projekt Alfa")), CoordinateKind.Leaf),
        Vector(duration(60), duration(60), count(1), None, None, None, duration(60), duration(60), count(1))
      ),
      row("Użytkownik Żółty", Some("Suma częściowa"), Seq(180, 90, 2, 180, 180, 1, 360, 120, 3), CoordinateKind.Subtotal),
      row("Razem", None, Seq(180, 90, 2, 180, 180, 1, 360, 120, 3), CoordinateKind.GrandTotal)
    )
  )

  private val canonicalCsv =
    "Person,Initiative,Lipiec 2026 / Suma czasu,Lipiec 2026 / Średni czas,Lipiec 2026 / Liczba wpisów," +
      "Sierpień 2026 / Suma czasu,Sierpień 2026 / Średni czas,Sierpień 2026 / Liczba wpisów," +
      "Razem / Suma czasu,Razem / Średni czas,Razem / Liczba wpisów\r\n" +
      "Użytkownik Żółty,Projekt Żółty,02:00,02:00,1,03:00,03:00,1,05:00,02:30,2\r\n" +
      "Użytkownik Żółty,Projekt Alfa,01:00,01:00,1,,,,01:00,01:00,1\r\n" +
      "Użytkownik Żółty,Suma częściowa,03:00,01:30,2,03:00,03:00,1,06:00,02:00,3\r\n" +
      "Razem,,03:00,01:30,2,03:00,03:00,1,06:00,02:00,3\r\n"

  private def row(
    firstLabel: String,
    secondLabel: Option[String],
    rawValues: Seq[Long],
    kind: CoordinateKind = CoordinateKind.Leaf
  ): DataRow =
    DataRow(
      RowHeader(Vector(Some(firstLabel), secondLabel), kind),
      rawValues.grouped(3).flatMap(values => Vector(duration(values(0)), duration(values(1)), count(values(2)))).toVector
    )

  private def singleValueTable(value: Value, measure: WorkUnitPivotTableValue): Table =
    Table(
      rowFieldLabels = Vector.empty,
      columns = Vector(column(Vector.empty, measure)),
      rows = Vector(DataRow(RowHeader(Vector.empty, CoordinateKind.Leaf), Vector(Some(value))))
    )

  private def column(labels: Vector[String], measure: WorkUnitPivotTableValue): ColumnHeader =
    ColumnHeader(emptyCoordinate, labels, measure)

  private def duration(minutes: Long): Option[Value] = Some(DurationMinutes(BigInt(minutes)))
  private def count(value: Long): Option[Value]      = Some(Count(value))

  private def csvBytes(value: String): Array[Byte] = Utf8Bom ++ value.getBytes(UTF_8)

  private final class CountingOutputStream extends OutputStream {
    var totalBytes: Long       = 0L
    var maximumWriteBytes: Int = 0

    override def write(value: Int): Unit = recordWrite(1)

    override def write(
      bytes: Array[Byte],
      offset: Int,
      length: Int
    ): Unit = recordWrite(length)

    private def recordWrite(length: Int): Unit = {
      totalBytes += length
      maximumWriteBytes = maximumWriteBytes.max(length)
    }
  }
}
