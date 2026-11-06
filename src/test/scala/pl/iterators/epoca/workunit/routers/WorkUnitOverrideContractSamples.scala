package pl.iterators.epoca.workunit.routers

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax._
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitTaskId
import pl.iterators.epoca.workunit.domain.WorkUnitTaskOverride
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

object WorkUnitOverrideContractSamples {
  val correctionId: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
  val undoId: UUID       = UUID.fromString("66666666-6666-4666-8666-666666666666")
  val current: WorkUnitDto = decode[WorkUnitDto](
    """{"id":"44444444-4444-4444-8444-444444444444","userId":"33333333-3333-4333-8333-333333333333","userFullName":"Contract Actor","date":"2026-09-22","duration":"PT2H","entryType":"Worklog","description":"Original worklog","taskId":"SOURCE-1","state":"Active","createdAt":"2026-09-22T08:00:00Z","modifiedAt":"2026-09-22T09:00:00Z","projectClient":{"projectId":"11111111-1111-4111-8111-111111111111","projectName":"Source Project","projectDescription":null,"clientId":"22222222-2222-4222-8222-222222222222","clientName":"Source Client","clientColor":"#336699","clientTexture":""},"effectiveProjectClient":{"projectId":"11111111-1111-4111-8111-111111111111","projectName":"Source Project","projectDescription":null,"clientId":"22222222-2222-4222-8222-222222222222","clientName":"Source Client","clientColor":"#336699","clientTexture":""},"effectiveTaskId":null,"revision":7,"override":{"active":true,"hasHistory":true,"version":7,"projectId":null,"taskId":{"mode":"Clear"},"lastChangedBy":{"id":"33333333-3333-4333-8333-333333333333","displayName":"Contract Actor"},"lastChangedAt":"2026-09-22T09:00:00Z","permissions":{"canEdit":true,"canDelete":true,"canViewHistory":true,"allowedProjectIds":["11111111-1111-4111-8111-111111111111"]},"undo":{"candidateEventId":"55555555-5555-4555-8555-555555555555","canUndo":true,"reason":null}}}"""
  ).fold(throw _, identity)
  private val actor  = current.`override`.lastChangedBy.get
  private val undoAt = Instant.parse("2026-09-22T10:00:00Z")
  val afterUndo: WorkUnitDto = current.copy(
    effectiveTaskId = Some(WorkUnitTaskId("SOURCE-1")),
    revision = 8,
    `override` = current.`override`.copy(
      active = false,
      version = 8,
      taskId = WorkUnitTaskOverride("Inherit"),
      lastChangedAt = Some(undoAt),
      undo = Some(WorkUnitOverrideUndoDto(None, false, Some("LegacyBoundary")))
    )
  )
  val request: UndoWorkUnitOverrideRequest = UndoWorkUnitOverrideRequest(7, correctionId)

  private val project = current.projectClient.get
  private val source = Json.obj(
    "projectClient" -> Json.obj(
      "projectId"   -> project.projectId.asJson,
      "projectName" -> project.projectName.asJson,
      "clientId"    -> project.clientId.asJson,
      "clientName"  -> project.clientName.asJson
    ),
    "taskId" -> current.taskId.asJson
  )
  private val before = Json.obj(
    "source"            -> source,
    "effective"         -> source,
    "projectId"         -> Json.Null,
    "taskIdOverrideSet" -> false.asJson,
    "taskId"            -> WorkUnitTaskOverride("Inherit").asJson
  )
  private val after = before.deepMerge(
    Json.obj(
      "effective"         -> source.deepMerge(Json.obj("taskId" -> Json.Null)),
      "taskIdOverrideSet" -> true.asJson,
      "taskId"            -> WorkUnitTaskOverride("Clear").asJson
    )
  )
  val history: WorkUnitOverrideHistoryPage = WorkUnitOverrideHistoryPage(
    2,
    1,
    25,
    8,
    Seq(
      WorkUnitOverrideHistoryDto(
        undoId,
        8,
        "Undo",
        actor,
        undoAt,
        "WorkUnitOverride",
        Some(after),
        Some(before),
        None,
        None,
        revertsEventId = Some(correctionId)
      ),
      WorkUnitOverrideHistoryDto(
        correctionId,
        7,
        "Replace",
        actor,
        current.`override`.lastChangedAt.get,
        "WorkUnitOverride",
        Some(before),
        Some(after),
        None,
        None,
        revertedBy = Some(WorkUnitOverrideRevertedByDto(undoId, actor, undoAt))
      )
    )
  )

  def capture(name: String, response: Json): Unit = {
    val directory = Path.of("target", "undo-contract-samples")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), response.spaces2, StandardCharsets.UTF_8)
    ()
  }
}
