package pl.iterators.epoca.workunit.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateRequest

import java.time.{Duration, LocalDate}
import java.util.UUID

class WorkUnitCreateRequestNormalizerSpec extends SpecificationLike {
  private val actorId     = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val delegatedId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
  private val duration    = WorkUnitDuration(Duration.ofMinutes(30L))

  private val baseRequest = WorkUnitCreateRequest(
    projectId = ProjectId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
    date = WorkUnitDate(LocalDate.parse("2026-07-29")),
    duration = Some(duration),
    description = WorkUnitDescription("Normalization contract"),
    taskId = Some(WorkUnitTaskId("CT-508")),
    isDraft = false
  )

  private def normalize(request: WorkUnitCreateRequest): NormalizedWorkUnitCreateRequest =
    WorkUnitCreateRequestNormalizer.normalize(actorId, request)

  "normalize" should {
    "default an omitted entry type to Worklog and an omitted user to the actor" in {
      normalize(baseRequest) shouldEqual NormalizedWorkUnitCreateRequest(
        projectId = baseRequest.projectId,
        date = baseRequest.date,
        duration = baseRequest.duration,
        description = baseRequest.description,
        taskId = baseRequest.taskId,
        isDraft = baseRequest.isDraft,
        entryType = WorkUnitEntryType.Worklog,
        targetUserId = actorId
      )
    }

    "make omitted and explicit legacy defaults exact semantic aliases" in {
      val explicitDefaults = baseRequest.copy(entryType = Some(WorkUnitEntryType.Worklog), userId = Some(actorId))

      normalize(baseRequest) shouldEqual normalize(explicitDefaults)
    }

    "preserve an explicit entry type and target user" in {
      val request =
        baseRequest.copy(entryType = Some(WorkUnitEntryType.Note), userId = Some(delegatedId), duration = Some(WorkUnitDuration(Duration.ZERO)))

      val normalized = normalize(request)

      normalized.entryType shouldEqual WorkUnitEntryType.Note
      normalized.targetUserId shouldEqual delegatedId
    }

    "make an omitted and explicit zero note duration exact semantic aliases" in {
      val omitted  = baseRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None)
      val explicit = omitted.copy(duration = Some(WorkUnitDuration(Duration.ZERO)))

      normalize(omitted) shouldEqual normalize(explicit)
      normalize(omitted).duration shouldEqual Some(WorkUnitDuration(Duration.ZERO))
    }

    "preserve invalid non-neutral note durations for fingerprinting and validation" in {
      val invalid =
        Seq(Duration.ofSeconds(1L), Duration.ofSeconds(-1L), Duration.ofMillis(500L), Duration.ofMillis(-500L)).map(WorkUnitDuration.apply)

      invalid.map { value =>
        normalize(baseRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = Some(value))).duration
      } shouldEqual invalid.map(Some.apply)
    }

    "leave an omitted worklog duration absent" in {
      normalize(baseRequest.copy(entryType = Some(WorkUnitEntryType.Worklog), duration = None)).duration should beNone
    }
  }
}
