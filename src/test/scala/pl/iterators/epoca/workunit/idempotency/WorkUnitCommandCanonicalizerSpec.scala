package pl.iterators.epoca.workunit.idempotency

import io.circe.Json
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitCommandCanonicalizer.{CanonicalizationError, Version1, Version2}
import pl.iterators.epoca.workunit.idempotency.WorkUnitIdempotencyDomain._
import pl.iterators.epoca.workunit.idempotency.WorkUnitWriteCommand.{Create, Update}
import pl.iterators.epoca.workunit.services.WorkUnitCreateService.WorkUnitCreateRequest
import pl.iterators.epoca.workunit.services.WorkUnitUpdateService.WorkUnitUpdateRequest

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Duration, LocalDate}
import java.util.UUID

class WorkUnitCommandCanonicalizerSpec extends SpecificationLike {
  private val projectId       = ProjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val otherProjectId  = ProjectId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
  private val workUnitId      = WorkUnitId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
  private val otherWorkUnitId = WorkUnitId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
  private val actorId         = UserId(UUID.fromString("55555555-5555-5555-5555-555555555555"))
  private val otherUserId     = UserId(UUID.fromString("66666666-6666-6666-6666-666666666666"))

  private val createRequest = WorkUnitCreateRequest(
    projectId = projectId,
    date = WorkUnitDate(LocalDate.parse("2026-07-27")),
    duration = Some(WorkUnitDuration(Duration.ofMinutes(90L))),
    description = WorkUnitDescription("Canonical work"),
    taskId = Some(WorkUnitTaskId("CT-528")),
    isDraft = false
  )

  private val updateRequest = WorkUnitUpdateRequest(
    projectId = Some(projectId),
    duration = Some(WorkUnitDuration(Duration.ofMinutes(90L))),
    description = Some(WorkUnitDescription("Canonical work")),
    taskId = Some(WorkUnitTaskId("CT-528")),
    date = Some(WorkUnitDate(LocalDate.parse("2026-07-27"))),
    shouldFinalize = false
  )

  private def canonical(command: WorkUnitWriteCommand): CanonicalWorkUnitCommand =
    canonical(Version1, command)

  private def canonical(version: WorkUnitIdempotencyFingerprintVersion, command: WorkUnitWriteCommand): CanonicalWorkUnitCommand =
    WorkUnitCommandCanonicalizer.canonicalize(version, actorId, command).toOption.get

  private def sha256(value: String): WorkUnitIdempotencyFingerprint =
    WorkUnitIdempotencyFingerprint(
      MessageDigest
        .getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
    )

  private def assertAllFingerprintsChange(baseline: CanonicalWorkUnitCommand, changedCommands: Seq[(String, WorkUnitWriteCommand)]): Result =
    changedCommands.collect {
      case (field, command) if canonical(command).fingerprint == baseline.fingerprint => field
    } should beEmpty

  "WorkUnitCommandCanonicalizer" should {
    "produce identical canonical JSON and fingerprints for identical POST commands" in {
      val first  = canonical(Create(createRequest))
      val second = canonical(Create(createRequest.copy()))

      first.json shouldEqual second.json
      first.json.noSpaces shouldEqual
        """{"schemaVersion":1,"method":"POST","operation":"Create","targetWorkUnitId":null,"payload":{"projectId":"11111111-1111-1111-1111-111111111111","date":"2026-07-27","duration":"PT1H30M","description":"Canonical work","taskId":"CT-528","isDraft":false}}"""
      first.fingerprint shouldEqual second.fingerprint
      first.fingerprint shouldEqual WorkUnitIdempotencyFingerprint("31def755896150972ec6f24067774a1b55f1623f75260a1f42c85fb41193fc95")
    }

    "keep all semantically legacy self-Worklogs on the exact V1 golden" in {
      val requests = Seq(
        createRequest,
        createRequest.copy(entryType = Some(WorkUnitEntryType.Worklog)),
        createRequest.copy(userId = Some(actorId)),
        createRequest.copy(entryType = Some(WorkUnitEntryType.Worklog), userId = Some(actorId))
      )

      requests.map(request => WorkUnitCommandCanonicalizer.versionForNewClaim(actorId, Create(request))) shouldEqual Seq.fill(4)(Version1)
      requests.map(request => canonical(Create(request)).json.noSpaces).distinct shouldEqual Seq(
        """{"schemaVersion":1,"method":"POST","operation":"Create","targetWorkUnitId":null,"payload":{"projectId":"11111111-1111-1111-1111-111111111111","date":"2026-07-27","duration":"PT1H30M","description":"Canonical work","taskId":"CT-528","isDraft":false}}"""
      )
      requests.map(request => canonical(Create(request)).fingerprint).distinct shouldEqual Seq(
        WorkUnitIdempotencyFingerprint("31def755896150972ec6f24067774a1b55f1623f75260a1f42c85fb41193fc95")
      )
    }

    "select V2 for Notes, foreign targets, and Worklogs without a duration" in {
      val commands = Seq(
        Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None)),
        Create(createRequest.copy(userId = Some(otherUserId))),
        Create(createRequest.copy(duration = None))
      )

      commands.map(WorkUnitCommandCanonicalizer.versionForNewClaim(actorId, _)) shouldEqual Seq.fill(3)(Version2)
      canonical(Version2, commands.last).json.hcursor.downField("payload").downField("duration").focus should beSome(Json.Null)
    }

    "canonicalize semantic Note and actor aliases to the same V2 fingerprint" in {
      val aliases = Seq(
        createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None),
        createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = Some(WorkUnitDuration(Duration.ZERO))),
        createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None, userId = Some(actorId))
      )

      aliases.map(request => canonical(Version2, Create(request)).fingerprint).distinct.size shouldEqual 1
    }

    "emit the exact normalized V2 Create shape and field order" in {
      canonical(
        Version2,
        Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None, userId = Some(otherUserId)))
      ).json.noSpaces shouldEqual
        """{"schemaVersion":2,"method":"POST","operation":"Create","targetWorkUnitId":null,"payload":{"projectId":"11111111-1111-1111-1111-111111111111","date":"2026-07-27","duration":"PT0S","description":"Canonical work","taskId":"CT-528","isDraft":false,"entryType":"Note","targetUserId":"66666666-6666-6666-6666-666666666666"}}"""
    }

    "keep invalid nonzero Note durations distinct instead of aliasing validation errors" in {
      val positive = canonical(
        Version2,
        Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = Some(WorkUnitDuration(Duration.ofMinutes(1)))))
      )
      val negative = canonical(
        Version2,
        Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = Some(WorkUnitDuration(Duration.ofMinutes(-1)))))
      )
      val neutral = canonical(Version2, Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None)))

      Seq(positive.fingerprint, negative.fingerprint, neutral.fingerprint).distinct.size shouldEqual 3
      positive.json.hcursor.downField("payload").get[String]("duration") should beRight("PT1M")
      negative.json.hcursor.downField("payload").get[String]("duration") should beRight("PT-1M")
    }

    "include type, target, duration, and every legacy Create field in the V2 fingerprint" in {
      val baselineRequest = createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None)
      val baseline        = canonical(Version2, Create(baselineRequest))
      val changes = Seq(
        "entryType"    -> Create(baselineRequest.copy(entryType = Some(WorkUnitEntryType.Worklog))),
        "targetUserId" -> Create(baselineRequest.copy(userId = Some(otherUserId))),
        "duration"     -> Create(baselineRequest.copy(duration = Some(WorkUnitDuration(Duration.ofMinutes(1))))),
        "projectId"    -> Create(baselineRequest.copy(projectId = otherProjectId)),
        "date"         -> Create(baselineRequest.copy(date = WorkUnitDate(LocalDate.parse("2026-07-28")))),
        "description"  -> Create(baselineRequest.copy(description = WorkUnitDescription("Different work"))),
        "taskId"       -> Create(baselineRequest.copy(taskId = Some(WorkUnitTaskId("CT-529")))),
        "isDraft"      -> Create(baselineRequest.copy(isDraft = true))
      )

      changes.collect {
        case (field, command) if canonical(Version2, command).fingerprint == baseline.fingerprint => field
      } should beEmpty
    }

    "include every POST mutation field in the fingerprint" in {
      val baseline = canonical(Create(createRequest))
      val changes = Seq(
        "projectId"   -> Create(createRequest.copy(projectId = otherProjectId)),
        "date"        -> Create(createRequest.copy(date = WorkUnitDate(LocalDate.parse("2026-07-28")))),
        "duration"    -> Create(createRequest.copy(duration = Some(WorkUnitDuration(Duration.ofMinutes(91L))))),
        "description" -> Create(createRequest.copy(description = WorkUnitDescription("Different work"))),
        "taskId"      -> Create(createRequest.copy(taskId = Some(WorkUnitTaskId("CT-529")))),
        "isDraft"     -> Create(createRequest.copy(isDraft = true))
      )

      assertAllFingerprintsChange(baseline, changes)
    }

    "include PATCH method, target work unit ID, every mutation field, and shouldFinalize" in {
      val baseline = canonical(Update(workUnitId, updateRequest))
      val changes = Seq(
        "targetWorkUnitId" -> Update(otherWorkUnitId, updateRequest),
        "projectId"        -> Update(workUnitId, updateRequest.copy(projectId = Some(otherProjectId))),
        "date"             -> Update(workUnitId, updateRequest.copy(date = Some(WorkUnitDate(LocalDate.parse("2026-07-28"))))),
        "duration"         -> Update(workUnitId, updateRequest.copy(duration = Some(WorkUnitDuration(Duration.ofMinutes(91L))))),
        "description"      -> Update(workUnitId, updateRequest.copy(description = Some(WorkUnitDescription("Different work")))),
        "taskId"           -> Update(workUnitId, updateRequest.copy(taskId = Some(WorkUnitTaskId("CT-529")))),
        "shouldFinalize"   -> Update(workUnitId, updateRequest.copy(shouldFinalize = true))
      )

      baseline.json.noSpaces shouldEqual
        """{"schemaVersion":1,"method":"PATCH","operation":"Update","targetWorkUnitId":"33333333-3333-3333-3333-333333333333","payload":{"projectId":"11111111-1111-1111-1111-111111111111","date":"2026-07-27","duration":"PT1H30M","description":"Canonical work","taskId":"CT-528","shouldFinalize":false}}"""
      assertAllFingerprintsChange(baseline, changes)
    }

    "represent every absent optional PATCH field as an explicit JSON null" in {
      val request = WorkUnitUpdateRequest(None, None, None, None, None, shouldFinalize = false)
      val payload = canonical(Update(workUnitId, request)).json.hcursor.downField("payload").focus

      payload should beSome(
        Json.obj(
          "projectId"      -> Json.Null,
          "date"           -> Json.Null,
          "duration"       -> Json.Null,
          "description"    -> Json.Null,
          "taskId"         -> Json.Null,
          "shouldFinalize" -> Json.False
        )
      )
      payload.flatMap(_.asObject).map(_.keys.toList) should beSome(List("projectId", "date", "duration", "description", "taskId", "shouldFinalize"))
    }

    "canonicalize V2 Update with the legacy PATCH fields only" in {
      val result = canonical(Version2, Update(workUnitId, updateRequest))

      WorkUnitCommandCanonicalizer.versionForNewClaim(actorId, Update(workUnitId, updateRequest)) shouldEqual Version1
      result.json.noSpaces shouldEqual
        """{"schemaVersion":2,"method":"PATCH","operation":"Update","targetWorkUnitId":"33333333-3333-3333-3333-333333333333","payload":{"projectId":"11111111-1111-1111-1111-111111111111","date":"2026-07-27","duration":"PT1H30M","description":"Canonical work","taskId":"CT-528","shouldFinalize":false}}"""
      result.json.hcursor.downField("payload").downField("entryType").focus should beNone
      result.json.hcursor.downField("payload").downField("userId").focus should beNone
    }

    "keep Create and Update fingerprints in distinct identity domains" in {
      val create = canonical(Create(createRequest))
      val update = canonical(Update(workUnitId, updateRequest))

      create.fingerprint shouldNotEqual update.fingerprint
    }

    "reject V1 commands that cannot represent their effective semantics before building JSON" in {
      val commands = Seq(
        Create(createRequest.copy(entryType = Some(WorkUnitEntryType.Note), duration = None)),
        Create(createRequest.copy(userId = Some(otherUserId))),
        Create(createRequest.copy(duration = None))
      )

      commands.map(WorkUnitCommandCanonicalizer.canonicalize(Version1, actorId, _)) shouldEqual
        Seq.fill(3)(Left(CanonicalizationError.V1NotRepresentable))
    }

    "reject unsupported versions without falling back to a known schema" in {
      WorkUnitCommandCanonicalizer.canonicalize(999, actorId, Create(createRequest)) shouldEqual
        Left(CanonicalizationError.VersionUnsupported)
    }

    "use lowercase 64-character SHA-256 hex of UTF-8 json.noSpaces" in {
      val result                                         = canonical(Create(createRequest))
      val version: WorkUnitIdempotencyFingerprintVersion = result.version
      val fingerprint: WorkUnitIdempotencyFingerprint    = result.fingerprint

      version shouldEqual Version1
      result.json.hcursor.get[Int]("schemaVersion") should beRight(Version1)
      fingerprint.matches("^[0-9a-f]{64}$") should beTrue
      fingerprint shouldEqual sha256(result.json.noSpaces)
    }
  }
}
