package pl.iterators.epoca.workunit.domain

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID

class WorkUnitOverrideDomainSpec extends SpecificationLike {
  private val now = Instant.parse("2026-09-17T10:00:00Z")
  private val source = WorkUnit(
    WorkUnitId(UUID.randomUUID()),
    UserId(UUID.randomUUID()),
    OrganizationId(UUID.randomUUID()),
    ProjectId(UUID.randomUUID()),
    WorkUnitDate(LocalDate.parse("2026-09-17")),
    WorkUnitDuration(Duration.ofHours(1)),
    WorkUnitEntryType.Worklog,
    WorkUnitDescription("Original"),
    Some(WorkUnitTaskId("SOURCE")),
    WorkUnitState.Active,
    WorkUnitCreatedAt(now),
    WorkUnitModifiedAt(now),
    None
  )
  private val target = ProjectId(UUID.randomUUID())

  "Work-unit override state" should {
    "inherit source project and task by default" in {
      source.effectiveProjectId shouldEqual source.projectId
      source.effectiveTaskId shouldEqual source.taskId
      source.overrideState.active should beFalse
      source.overrideState.hasHistory should beFalse
    }
    "set project and task independently without mutating source fields" in {
      val corrected = source.copy(overrideState =
        WorkUnitOverrideState(projectId = Some(target), taskIdOverrideSet = true, taskId = Some(WorkUnitTaskId("TARGET")))
      )
      corrected.effectiveProjectId shouldEqual target
      corrected.effectiveTaskId shouldEqual Some(WorkUnitTaskId("TARGET"))
      corrected.projectId shouldEqual source.projectId
      corrected.taskId shouldEqual source.taskId
      corrected.overrideState.active should beTrue
    }
    "distinguish explicit task clearing from inheritance" in {
      source.copy(overrideState = WorkUnitOverrideState(taskIdOverrideSet = true)).effectiveTaskId should beNone
      source.copy(overrideState = WorkUnitOverrideState(taskIdOverrideSet = true)).overrideState.active should beTrue
    }
    "normalize source-equal project, task and clearing an empty source to inheritance" in {
      WorkUnitOverrideState(projectId = Some(source.projectId), taskIdOverrideSet = true, taskId = source.taskId)
        .normalized(source) shouldEqual WorkUnitOverrideState()
      WorkUnitOverrideState(taskIdOverrideSet = true).normalized(source.copy(taskId = None)) shouldEqual WorkUnitOverrideState()
    }
    "preserve override state and advance revision on ordinary source edits" in {
      val original = source.copy(revision = 7, overrideState = WorkUnitOverrideState(projectId = Some(target), taskIdOverrideSet = true))
      val updated = original.updated(
        WorkUnitUpdateRequest(None, None, Some(WorkUnitDescription("Edited")), Some(WorkUnitTaskId("NEW")), None, false),
        now.plusSeconds(1)
      )
      updated.overrideState shouldEqual original.overrideState
      updated.revision shouldEqual 8L
      updated.taskId shouldEqual Some(WorkUnitTaskId("NEW"))
      updated.effectiveTaskId should beNone
    }
    "validate task modes and count Unicode code points after trimming" in {
      WorkUnitTaskOverride("Set", Some("  TARGET  ")).validated shouldEqual Right((true, Some(WorkUnitTaskId("TARGET"))))
      WorkUnitTaskOverride("Inherit", None).validated shouldEqual Right((false, None))
      WorkUnitTaskOverride("Clear", None).validated shouldEqual Right((true, None))
      Seq(
        WorkUnitTaskOverride("Set", None),
        WorkUnitTaskOverride("Set", Some(" ")),
        WorkUnitTaskOverride("Clear", Some("X")),
        WorkUnitTaskOverride("Inherit", Some("")),
        WorkUnitTaskOverride("Unknown", None),
        WorkUnitTaskOverride("Set", Some("🚀" * 256))
      ).forall(_.validated.isLeft) should beTrue
      WorkUnitTaskOverride("Set", Some("🚀" * 255)).validated.isRight should beTrue
    }
  }
}
