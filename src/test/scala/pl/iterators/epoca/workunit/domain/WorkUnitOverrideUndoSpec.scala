package pl.iterators.epoca.workunit.domain

import io.circe.Json
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitId, WorkUnitTaskId}

import java.time.Instant
import java.util.UUID

class WorkUnitOverrideUndoSpec extends SpecificationLike {
  private val before = Json.obj("projectId" -> Json.Null, "taskIdOverrideSet" -> Json.True, "taskId" -> Json.obj("mode" -> Json.fromString("Clear")))
  private val event = WorkUnitOverrideHistory(
    UUID.randomUUID(),
    WorkUnitId(UUID.randomUUID()),
    1,
    "Replace",
    UserId(UUID.randomUUID()),
    UserFullName("Actor"),
    Instant.now(),
    "WorkUnitOverride",
    Some(before),
    Some(before)
  )

  "Undo ancestry" should {
    "select only a current full forward event or the remaining parent of Undo" in {
      WorkUnitOverrideUndo.candidateId(Some(event), 1) shouldEqual Some(event.id)
      WorkUnitOverrideUndo.candidateId(Some(event.copy(operation = "Clear")), 1) shouldEqual Some(event.id)
      WorkUnitOverrideUndo.candidateId(Some(event.copy(operation = "Undo", parentCorrectionId = Some(event.id))), 1) shouldEqual Some(event.id)
      WorkUnitOverrideUndo.candidateId(Some(event.copy(operation = "Undo")), 1) should beNone
      WorkUnitOverrideUndo.candidateId(Some(event), 2) should beNone
      WorkUnitOverrideUndo.candidateId(Some(event.copy(provenance = "LegacyPmSnapshot")), 1) should beNone
      WorkUnitOverrideUndo.candidateId(Some(event.copy(operation = "Future")), 1) should beNone
      WorkUnitOverrideUndo.candidateId(None, 0) should beNone
    }
    "restore Clear, Inherit and Set from explicit complete state" in {
      WorkUnitOverrideUndo.restore(event) shouldEqual Right(WorkUnitOverrideState(taskIdOverrideSet = true))
      WorkUnitOverrideUndo.restore(
        event.copy(before =
          Some(before.deepMerge(Json.obj("taskIdOverrideSet" -> Json.False, "taskId" -> Json.obj("mode" -> Json.fromString("Inherit")))))
        )
      ) shouldEqual Right(WorkUnitOverrideState())
      val set = before.deepMerge(Json.obj("taskId" -> Json.obj("mode" -> Json.fromString("Set"), "value" -> Json.fromString("A"))))
      WorkUnitOverrideUndo.restore(event.copy(before = Some(set))) shouldEqual Right(
        WorkUnitOverrideState(taskIdOverrideSet = true, taskId = Some(WorkUnitTaskId("A")))
      )
      val project = ProjectId(UUID.randomUUID())
      WorkUnitOverrideUndo.restore(
        event.copy(before = Some(before.deepMerge(Json.obj("projectId" -> Json.fromString(project.toString)))))
      ) shouldEqual Right(WorkUnitOverrideState(projectId = Some(project), taskIdOverrideSet = true))
    }
    "reject missing, legacy, future and inconsistent state" in {
      val futureTask = before.deepMerge(Json.obj("taskId" -> Json.obj("mode" -> Json.fromString("Future"))))
      Seq(
        event.copy(before = None),
        event.copy(before = Some(Json.obj())),
        event.copy(before = Some(before.mapObject(_.remove("projectId")))),
        event.copy(before = Some(before.deepMerge(Json.obj("taskIdOverrideSet" -> Json.False)))),
        event.copy(before = Some(before.deepMerge(Json.obj("projectId" -> Json.fromString("not-a-uuid"))))),
        event.copy(before = Some(futureTask)),
        event.copy(provenance = "LegacyPmSnapshot"),
        event.copy(operation = "Future"),
        event.copy(operation = "Undo")
      ).forall(WorkUnitOverrideUndo.restore(_).isLeft) should beTrue
    }
  }
}
