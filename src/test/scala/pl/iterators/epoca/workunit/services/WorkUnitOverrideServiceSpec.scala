package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.syntax._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepositoryImpl
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.{OrganizationRepositoryImpl, OrganizationUserRepositoryImpl}
import pl.iterators.epoca.project.domain.ProjectDomain._
import pl.iterators.epoca.project.domain.{Project, ProjectUser}
import pl.iterators.epoca.project.repositories.{ProjectRepositoryImpl, ProjectUserRepositoryImpl}
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepositoryImpl
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.{DBIOInstances, PostgresIOTransactor}
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOverrideHistory, WorkUnitOverrideState, WorkUnitTaskOverride}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.repositories.{WorkUnitOverrideHistoryRepositoryImpl, WorkUnitRepositoryImpl}
import pl.iterators.epoca.workunit.services.WorkUnitOverrideResult._
import slick.dbio.DBIO

import java.time.{Duration, Instant}
import java.util.UUID

class WorkUnitOverrideServiceSpec extends PgRepositorySpec {
  private implicit val runtime: IORuntime             = IORuntime.global
  private implicit val clock: cats.effect.Clock[DBIO] = DBIOInstances.dbioInstance(ec)
  private val organizations                           = new OrganizationRepositoryImpl()
  private val memberships                             = new OrganizationUserRepositoryImpl()
  private val clients                                 = new ClientRepositoryImpl()
  private val projects                                = new ProjectRepositoryImpl()
  private val projectUsers                            = new ProjectUserRepositoryImpl()
  private val users                                   = new UserRepositoryImpl()
  private val units                                   = new WorkUnitRepositoryImpl()
  private val history                                 = new WorkUnitOverrideHistoryRepositoryImpl()
  private val dto                                     = new WorkUnitDtoService(clients, users, projects)
  private val sut =
    new WorkUnitOverrideService(new PostgresIOTransactor(db), organizations, memberships, projects, projectUsers, clients, users, units, history, dto)

  "Work-unit override writes" should {
    "bound reversal metadata to the history snapshot across pages and later undo writes" in withDatabase {
      for {
        f        <- fixture()
        a        <- replaceTask(f, 0, "A")
        b        <- replaceTask(f, 1, "B")
        before   <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(1), Some(1))
        restored <- undo(f, b)
        old      <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(1), Some(1))
        second   <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 3, Some(1), Some(2))
        third    <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 3, Some(1), Some(3))
        newest   <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 3, Some(1), Some(1))
      } yield {
        val eventA    = ok(a).`override`.undo.get.candidateEventId.get
        val eventB    = ok(b).`override`.undo.get.candidateEventId.get
        val undoEvent = newest.toOption.get.results.head
        old shouldEqual before
        old.toOption.get.count shouldEqual 2
        old.toOption.get.results.head.asJson.hcursor.get[Option[Json]]("revertedBy") shouldEqual Right(None)
        second.toOption.get.count shouldEqual 3
        second.toOption.get.results.head.id shouldEqual eventB
        second.toOption.get.results.head.asJson.hcursor.get[Option[UUID]]("parentCorrectionId") shouldEqual Right(Some(eventA))
        val revertedBy = second.toOption.get.results.head.asJson.hcursor.downField("revertedBy")
        revertedBy.get[UUID]("eventId") shouldEqual Right(undoEvent.id)
        revertedBy.get[Json]("actor") shouldEqual Right(undoEvent.actor.asJson)
        revertedBy.get[Instant]("changedAt") shouldEqual Right(undoEvent.changedAt)
        undoEvent.asJson.hcursor.get[Option[UUID]]("revertsEventId") shouldEqual Right(Some(eventB))
        undoEvent.asJson.hcursor.get[Option[UUID]]("parentCorrectionId") shouldEqual Right(Some(eventA))
        third.toOption.get.results.head.id shouldEqual eventA
        ok(restored).`override`.undo.get.candidateEventId shouldEqual Some(eventA)
      }
    }
    "distinguish an empty stack from legacy history and scope worklog lookup to the organization" in withDatabase {
      for {
        f        <- fixture()
        foreign  <- fixture()
        empty    <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        noTarget <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(0, UUID.randomUUID()))
        wrongOrg <- sut.undoAction(f.auth, foreign.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(0, UUID.randomUUID()))
        event = WorkUnitOverrideHistory(
                  UUID.randomUUID(),
                  f.unit.id,
                  1,
                  "Replace",
                  f.auth.id,
                  pl.iterators.epoca.user.domain.UserDomain.UserFullName("Actor"),
                  Instant.now(),
                  "LegacyPmSnapshot",
                  None,
                  None
                )
        _ <- history.insert(event)
        _ <- units.update(
               f.unit.copy(overrideState =
                 WorkUnitOverrideState(version = 1, hasHistory = true, lastChangedBy = Some(f.auth.id), lastChangedAt = Some(event.changedAt))
               )
             )
        legacy   <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        next     <- replaceTask(f, 1, "A")
        restored <- undo(f, next)
        events   <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        ok(empty).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("NoCorrection"))
        noTarget shouldEqual StaleUndoTarget(0)
        wrongOrg shouldEqual WorkUnitNotFound
        ok(legacy).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("LegacyBoundary"))
        ok(restored).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("LegacyBoundary"))
        events.find(_.version == 2).get.parentCorrectionId should beNone
      }
    }
    "restore project client and inheritance against a later null source task" in withDatabase {
      for {
        f            <- fixture()
        sourceClient <- clients.find(pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters(id = Some(f.target.clientId)))
        otherClient  <- clients.insert(sourceClient.get.copy(id = pl.iterators.epoca.client.domain.ClientDomain.ClientId(UUID.randomUUID())))
        _            <- projects.update(f.target.copy(clientId = otherClient.id))
        a <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        b <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        _ <- units.get(f.unit.id).flatMap(unit => units.update(unit.copy(taskId = None)))
        restored <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(3, ok(b).`override`.undo.get.candidateEventId.get))
      } yield {
        ok(restored).effectiveProjectClient shouldEqual ok(a).effectiveProjectClient
        ok(restored).effectiveTaskId should beNone
        ok(restored).`override`.taskId.mode shouldEqual "Inherit"
        ok(restored).projectClient shouldEqual ok(a).projectClient
      }
    }
    "disable restoration to a project outside the owner's current scope" in withDatabase {
      for {
        f <- fixture()
        _ <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        cleared <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        _       <- memberships.upsert(f.member.copy(roles = Seq(OrganizationUserRole.Employee)))
        _ <-
          projectUsers.insert(
            ProjectUser(ProjectUserId(UUID.randomUUID()), f.unit.projectId, f.auth.id, ProjectRole.Owner, ProjectUserCreatedAt(Instant.now()), None)
          )
        current  <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        rejected <- undo(f, cleared)
        saved    <- units.get(f.unit.id)
        count    <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        ok(current).`override`.permissions.canEdit should beTrue
        ok(current).`override`.undo.get.reason shouldEqual Some("TargetUnavailable")
        rejected shouldEqual ProjectUnavailable
        saved.revision shouldEqual 2L
        count shouldEqual 2
      }
    }
    "branch from the remaining head without resurrecting an undone correction" in withDatabase {
      for {
        f      <- fixture()
        a      <- replaceTask(f, 0, "A")
        b      <- replaceTask(f, 1, "B")
        c      <- replaceTask(f, 2, "C")
        _      <- undo(f, c)
        d      <- replaceTask(f, 4, "D")
        backB  <- undo(f, d)
        backA  <- undo(f, backB)
        source <- undo(f, backA)
        events <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        ok(backB).effectiveTaskId shouldEqual Some(WorkUnitTaskId("B"))
        ok(backA).effectiveTaskId shouldEqual Some(WorkUnitTaskId("A"))
        ok(source).effectiveTaskId shouldEqual f.unit.taskId
        events.find(_.id == ok(d).`override`.undo.get.candidateEventId.get).get.parentCorrectionId shouldEqual ok(
          b
        ).`override`.undo.get.candidateEventId
        events.filter(_.operation == "Undo").flatMap(_.revertsEventId).toSet shouldEqual Set(a, b, c, d).map(value =>
          ok(value).`override`.undo.get.candidateEventId.get
        )
      }
    }
    "undo an inactive Clear and preserve project, task intention and later source edits" in withDatabase {
      for {
        f <- fixture()
        a <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Clear")))
        cleared <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        _ <- units
               .get(f.unit.id)
               .flatMap(unit => units.update(unit.copy(taskId = Some(WorkUnitTaskId("LATER")), description = WorkUnitDescription("Later source"))))
        restored <-
          sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(3, ok(cleared).`override`.undo.get.candidateEventId.get))
        source <- undo(f, restored)
        saved  <- units.get(f.unit.id)
      } yield {
        ok(cleared).`override`.active should beFalse
        ok(cleared).`override`.undo.get.canUndo should beTrue
        ok(restored).effectiveProjectClient.map(_.projectId) shouldEqual Some(f.target.id)
        ok(restored).`override`.taskId.mode shouldEqual "Clear"
        ok(restored).`override`.undo.get.candidateEventId shouldEqual ok(a).`override`.undo.get.candidateEventId
        ok(source).effectiveTaskId shouldEqual Some(WorkUnitTaskId("LATER"))
        saved.description shouldEqual WorkUnitDescription("Later source")
        saved.projectId shouldEqual f.unit.projectId
      }
    }
    "pop a valid correction even when normalization makes Undo an effective no-op" in withDatabase {
      for {
        f      <- fixture()
        _      <- replaceTask(f, 0, "A")
        b      <- replaceTask(f, 1, "SOURCE")
        _      <- units.get(f.unit.id).flatMap(unit => units.update(unit.copy(taskId = Some(WorkUnitTaskId("A")))))
        result <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(3, ok(b).`override`.undo.get.candidateEventId.get))
        events <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        ok(result).revision shouldEqual 4L
        ok(result).`override`.version shouldEqual 3L
        ok(result).`override`.active should beFalse
        ok(result).`override`.undo.get.canUndo should beTrue
        events.map(_.operation) shouldEqual Seq("Undo", "Replace", "Replace")
      }
    }
    "reject missing, foreign, future, non-head and already undone targets without mutations" in withDatabase {
      for {
        f       <- fixture()
        foreign <- fixture()
        other   <- replaceTask(foreign, 0, "FOREIGN")
        a       <- replaceTask(f, 0, "A")
        b       <- replaceTask(f, 1, "B")
        ids = Seq(UUID.randomUUID(), ok(other).`override`.undo.get.candidateEventId.get, ok(a).`override`.undo.get.candidateEventId.get)
        rejected <- DBIO.sequence(ids.map(id => sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(2, id))))
        future   <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(99, ok(b).`override`.undo.get.candidateEventId.get))
        negative <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(-1, UUID.randomUUID()))
        back     <- undo(f, b)
        again    <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(3, ok(b).`override`.undo.get.candidateEventId.get))
        count    <- history.count(f.unit.id, Long.MaxValue)
        saved    <- units.get(f.unit.id)
      } yield {
        rejected shouldEqual Seq.fill(3)(StaleUndoTarget(2))
        future shouldEqual StaleRevision(2)
        negative shouldEqual InvalidRequest("expectedRevision")
        again shouldEqual StaleUndoTarget(3)
        saved.revision shouldEqual ok(back).revision
        count shouldEqual 3
      }
    }
    "stop at current-only and gapped historical boundaries without guessing source" in withDatabase {
      for {
        f        <- fixture()
        first    <- replaceTask(f, 0, "A")
        _        <- units.get(f.unit.id).flatMap(unit => units.update(unit.copy(overrideState = unit.overrideState.copy(version = 7))))
        boundary <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        rejected <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(2, ok(first).`override`.undo.get.candidateEventId.get))
        newer    <- replaceTask(f, 2, "B")
        restored <- undo(f, newer)
        events   <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        ok(boundary).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("LegacyBoundary"))
        rejected shouldEqual StaleUndoTarget(2)
        ok(restored).effectiveTaskId shouldEqual Some(WorkUnitTaskId("A"))
        ok(restored).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("LegacyBoundary"))
        events.map(_.version) shouldEqual Seq(9L, 8L, 1L)
        events.find(_.version == 8).get.parentCorrectionId should beNone
      }
    }
    "disable malformed snapshots and reject their undo explicitly" in withDatabase {
      for {
        f <- fixture()
        event = WorkUnitOverrideHistory(
                  UUID.randomUUID(),
                  f.unit.id,
                  1,
                  "Replace",
                  f.auth.id,
                  pl.iterators.epoca.user.domain.UserDomain.UserFullName("Actor"),
                  Instant.now(),
                  "WorkUnitOverride",
                  Some(Json.obj()),
                  Some(Json.obj())
                )
        _ <- history.insert(event)
        _ <- units.update(
               f.unit.copy(overrideState =
                 WorkUnitOverrideState(version = 1, hasHistory = true, lastChangedBy = Some(f.auth.id), lastChangedAt = Some(event.changedAt))
               )
             )
        current <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        result  <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(1, event.id))
        count   <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        ok(current).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(Some(event.id), false, Some("TargetUnavailable"))
        result shouldEqual InvalidRequest("targetEventId")
        count shouldEqual 1
      }
    }
    "reject archived restoration targets but permit inheritance to an archived source" in withDatabase {
      for {
        f <- fixture()
        a <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        b <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        _ <- projects.update(f.target.copy(archivedAt = Some(ProjectArchivedAt(Instant.now()))))
        current   <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        rejected  <- undo(f, b)
        _         <- projects.update(f.target)
        restored  <- undo(f, b)
        source    <- projects.find(pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters(id = Some(f.unit.projectId)))
        _         <- projects.update(source.get.copy(archivedAt = Some(ProjectArchivedAt(Instant.now()))))
        inherited <- undo(f, restored)
      } yield {
        ok(current).`override`.undo.get.reason shouldEqual Some("TargetUnavailable")
        rejected shouldEqual ProjectUnavailable
        ok(restored).`override`.undo.get.candidateEventId shouldEqual ok(a).`override`.undo.get.candidateEventId
        ok(inherited).effectiveProjectClient.map(_.projectId) shouldEqual Some(f.unit.projectId)
      }
    }
    "revalidate current organization and source/effective owner scope before stale revision or target checks" in withDatabase {
      for {
        f <- fixture()
        a <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Clear")))
        _ <- memberships.upsert(f.member.copy(roles = Seq(OrganizationUserRole.Employee)))
        _ <-
          projectUsers.insert(
            ProjectUser(ProjectUserId(UUID.randomUUID()), f.unit.projectId, f.auth.id, ProjectRole.Owner, ProjectUserCreatedAt(Instant.now()), None)
          )
        missingEffective <- undo(f, a)
        _                <- memberships.upsert(f.member.copy(archivedAt = Some(OrganizationUserArchivedAt(Instant.now()))))
        revoked          <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(0, UUID.randomUUID()))
        _                <- memberships.upsert(f.member)
        _                <- units.get(f.unit.id).flatMap(unit => units.update(unit.copy(archivedAt = Some(WorkUnitArchivedAt(Instant.now())))))
        archived         <- undo(f, a)
        count            <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        missingEffective shouldEqual Forbidden
        revoked shouldEqual Forbidden
        archived shouldEqual Forbidden
        count shouldEqual 1
      }
    }
    "undo only the current correction once through C, B, A and source" in withDatabase {
      for {
        f <- fixture()
        a <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, None, WorkUnitTaskOverride("Set", Some("A"))))
        b <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(1, None, WorkUnitTaskOverride("Set", Some("B"))))
        c <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(2, None, WorkUnitTaskOverride("Set", Some("C"))))
        request = UndoWorkUnitOverrideRequest(3, ok(c).`override`.undo.get.candidateEventId.get)
        undoC  <- sut.undoAction(f.auth, f.org.slug, f.unit.id, request)
        retry  <- sut.undoAction(f.auth, f.org.slug, f.unit.id, request)
        undoB  <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(4, ok(undoC).`override`.undo.get.candidateEventId.get))
        undoA  <- sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(5, ok(undoB).`override`.undo.get.candidateEventId.get))
        saved  <- units.get(f.unit.id)
        events <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        ok(undoC).effectiveTaskId shouldEqual Some(WorkUnitTaskId("B"))
        ok(undoC).`override`.undo.get.candidateEventId shouldEqual ok(b).`override`.undo.get.candidateEventId
        retry shouldEqual StaleRevision(4)
        ok(undoB).effectiveTaskId shouldEqual Some(WorkUnitTaskId("A"))
        ok(undoB).`override`.undo.get.candidateEventId shouldEqual ok(a).`override`.undo.get.candidateEventId
        ok(undoA).effectiveTaskId shouldEqual f.unit.taskId
        ok(undoA).revision shouldEqual 6L
        ok(undoA).`override`.undo.get shouldEqual WorkUnitOverrideUndoDto(None, false, Some("NoCorrection"))
        saved.copy(revision = 0, overrideState = WorkUnitOverrideState()) shouldEqual f.unit
        events.map(_.operation) shouldEqual Seq("Undo", "Undo", "Undo", "Replace", "Replace", "Replace")
      }
    }
    "atomically retain source, return effective values and record complete immutable snapshots" in withDatabase {
      for {
        f <- fixture()
        result <- sut.replaceAction(
                    f.auth,
                    f.org.slug,
                    f.unit.id,
                    ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Set", Some("  TARGET  ")))
                  )
        saved  <- units.get(f.unit.id)
        events <- history.list(f.unit.id, 1, None)
      } yield {
        val value = ok(result)
        value.projectClient.map(_.projectId) shouldEqual Some(f.unit.projectId)
        value.taskId shouldEqual f.unit.taskId
        value.effectiveProjectClient.map(_.projectId) shouldEqual Some(f.target.id)
        value.effectiveTaskId shouldEqual Some(WorkUnitTaskId("TARGET"))
        value.revision shouldEqual 1L
        value.`override`.hasHistory should beTrue
        saved.copy(revision = 0, overrideState = WorkUnitOverrideState()) shouldEqual f.unit
        events.size shouldEqual 1
        events.head.provenance shouldEqual "WorkUnitOverride"
        events.head.before.get.hcursor.downField("source").get[String]("taskId") shouldEqual Right("SOURCE")
        events.head.after.get.hcursor.downField("effective").get[String]("taskId") shouldEqual Right("TARGET")
      }
    }
    "check revision before canonical no-op and append no duplicate event" in withDatabase {
      for {
        f <- fixture()
        request = ReplaceWorkUnitOverrideRequest(0, None, WorkUnitTaskOverride("Clear"))
        first <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, request)
        stale <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, request)
        noop  <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, request.copy(expectedRevision = 1))
        count <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        ok(first).effectiveTaskId should beNone
        ok(first).`override`.taskId.mode shouldEqual "Clear"
        stale shouldEqual StaleRevision(1)
        noop shouldEqual first
        count shouldEqual 1
      }
    }
    "normalize equal-source requests and restore inheritance while retaining history metadata" in withDatabase {
      for {
        f <- fixture()
        noop <- sut.replaceAction(
                  f.auth,
                  f.org.slug,
                  f.unit.id,
                  ReplaceWorkUnitOverrideRequest(0, Some(f.unit.projectId), WorkUnitTaskOverride("Set", Some("SOURCE")))
                )
        _ <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Clear")))
        cleared <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        events  <- history.list(f.unit.id, 2, None)
      } yield {
        ok(noop).revision shouldEqual 0L
        ok(cleared).`override`.active should beFalse
        ok(cleared).`override`.hasHistory should beTrue
        ok(cleared).effectiveTaskId shouldEqual Some(WorkUnitTaskId("SOURCE"))
        events.map(_.operation) shouldEqual Seq("Clear", "Replace")
      }
    }
    "conflict after a source edit without erasing the manager override" in withDatabase {
      for {
        f <- fixture()
        _ <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Clear")))
        before <- units.get(f.unit.id)
        _      <- units.update(before.copy(taskId = Some(WorkUnitTaskId("SOURCE-EDIT"))))
        fresh  <- units.get(f.unit.id)
        stale  <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
      } yield {
        fresh.revision shouldEqual 2L
        fresh.overrideState shouldEqual before.overrideState
        stale shouldEqual StaleRevision(2)
      }
    }
    "deny non-managers, revoked project owners and owners without both source and effective scope" in withDatabase {
      for {
        f      <- fixture(OrganizationUserRole.Employee)
        denied <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        _ <-
          projectUsers.insert(
            Seq(
              ProjectUser(ProjectUserId(UUID.randomUUID()), f.unit.projectId, f.auth.id, ProjectRole.Owner, ProjectUserCreatedAt(Instant.now()), None)
            )
          )
        targetDenied <-
          sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        _ <-
          projectUsers.insert(
            Seq(ProjectUser(ProjectUserId(UUID.randomUUID()), f.target.id, f.auth.id, ProjectRole.Owner, ProjectUserCreatedAt(Instant.now()), None))
          )
        allowed <-
          sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        _       <- memberships.upsert(f.member.copy(archivedAt = Some(OrganizationUserArchivedAt(Instant.now()))))
        revoked <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
      } yield {
        denied shouldEqual Forbidden
        targetDenied shouldEqual ProjectUnavailable
        ok(allowed).`override`.permissions.canEdit should beTrue
        revoked shouldEqual Forbidden
      }
    }
    "reject invalid tasks and unavailable targets without changing revision or history" in withDatabase {
      for {
        f       <- fixture()
        invalid <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, None, WorkUnitTaskOverride("Set", Some(" "))))
        missing <- sut.replaceAction(
                     f.auth,
                     f.org.slug,
                     f.unit.id,
                     ReplaceWorkUnitOverrideRequest(0, Some(ProjectId(UUID.randomUUID())), WorkUnitTaskOverride("Inherit"))
                   )
        saved <- units.get(f.unit.id)
        count <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        invalid shouldEqual InvalidRequest("taskId")
        missing shouldEqual ProjectNotFound
        saved.revision shouldEqual 0L
        count shouldEqual 0
      }
    }
    "advance above a current-only version without fabricating the missing history" in withDatabase {
      for {
        f <- fixture()
        _ <-
          units.update(
            f.unit.copy(overrideState =
              WorkUnitOverrideState(projectId = Some(f.target.id), version = 7, lastChangedBy = Some(f.auth.id), lastChangedAt = Some(Instant.now()))
            )
          )
        before <- units.get(f.unit.id)
        empty  <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 7, None, None)
        result <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, before.revision)
        events <- history.list(f.unit.id, Long.MaxValue, None)
      } yield {
        before.overrideState.hasHistory should beFalse
        empty.toOption.get.count shouldEqual 0
        ok(result).`override`.version shouldEqual 8L
        ok(result).`override`.hasHistory should beTrue
        events.map(_.version) shouldEqual Seq(8L)
      }
    }
    "validate history bounds and pagination while keeping a stable newest-first version snapshot" in withDatabase {
      for {
        f               <- fixture()
        _               <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, None, WorkUnitTaskOverride("Clear")))
        _               <- sut.deleteAction(f.auth, f.org.slug, f.unit.id, 1)
        newest          <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(1), Some(1))
        oldest          <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(1), Some(2))
        bounded         <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 1, None, None)
        invalidFuture   <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 3, None, None)
        invalidNegative <- sut.historyAction(f.auth, f.org.slug, f.unit.id, -1, None, None)
        invalidSize     <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(101), Some(1))
        missingNumber   <- sut.historyAction(f.auth, f.org.slug, f.unit.id, 2, Some(25), None)
      } yield {
        newest.toOption.get.count shouldEqual 2
        newest.toOption.get.results.map(_.version) shouldEqual Seq(2L)
        oldest.toOption.get.results.map(_.version) shouldEqual Seq(1L)
        bounded.toOption.get.count shouldEqual 1
        invalidFuture shouldEqual Left(InvalidRequest("upToVersion"))
        invalidNegative shouldEqual Left(InvalidRequest("upToVersion"))
        invalidSize shouldEqual Left(InvalidRequest("pageSize"))
        missingNumber shouldEqual Left(InvalidRequest("pageNumber"))
      }
    }
    "reject archived and foreign targets, stale JWT privileges and ineligible source states" in withDatabase {
      for {
        f      <- fixture(OrganizationUserRole.Employee)
        denied <- sut.getAction(f.auth.copy(isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(true)), f.org.slug, f.unit.id)
        _      <- memberships.upsert(f.member.copy(roles = Seq(OrganizationUserRole.Manager)))
        _      <- projects.update(f.target.copy(archivedAt = Some(ProjectArchivedAt(Instant.now()))))
        archived <-
          sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        foreign <- fixture()
        foreignTarget <- sut.replaceAction(
                           f.auth,
                           f.org.slug,
                           f.unit.id,
                           ReplaceWorkUnitOverrideRequest(0, Some(foreign.target.id), WorkUnitTaskOverride("Inherit"))
                         )
        _        <- units.update(f.unit.copy(state = WorkUnitState.Inactive))
        inactive <- sut.getAction(f.auth, f.org.slug, f.unit.id)
        count    <- history.count(f.unit.id, Long.MaxValue)
      } yield {
        denied shouldEqual Forbidden
        archived shouldEqual ProjectUnavailable
        foreignTarget shouldEqual ProjectNotFound
        inactive shouldEqual Forbidden
        count shouldEqual 0
      }
    }
    "filter effective projects separately from source and effective authorization before counting" in withDatabase {
      for {
        f <- fixture()
        _ <- sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(0, Some(f.target.id), WorkUnitTaskOverride("Inherit")))
        sourceRows <- units.list(WorkUnitFilters(organizationId = Some(f.org.id), projectIds = Some(Seq(f.unit.projectId))))
        targetRows <- units.list(WorkUnitFilters(organizationId = Some(f.org.id), projectIds = Some(Seq(f.target.id))))
        sourceOnly <- units.count(WorkUnitFilters(organizationId = Some(f.org.id), reportAuthorizedProjectIds = Some(Seq(f.unit.projectId))))
        targetOnly <- units.count(WorkUnitFilters(organizationId = Some(f.org.id), reportAuthorizedProjectIds = Some(Seq(f.target.id))))
      } yield {
        sourceRows should beEmpty
        targetRows.map(_.id) shouldEqual Seq(f.unit.id)
        sourceOnly shouldEqual 0
        targetOnly shouldEqual 0
      }
    }
  }

  private final class Fixture(
    val auth: AuthContext,
    val org: Organization,
    val unit: WorkUnit,
    val target: Project,
    val member: OrganizationUser)
  private def fixture(role: OrganizationUserRole = OrganizationUserRole.Manager): DBIO[Fixture] = for {
    actor <-
      users.insert(
        allGenerators[User].normal.generate.copy(isSuperAdmin = pl.iterators.epoca.user.domain.UserDomain.UserIsSuperAdmin(false), archivedAt = None)
      )
    org    <- organizations.insert(allGenerators[Organization].normal.generate.copy(createdBy = actor.id, modifiedBy = actor.id, archivedAt = None))
    member <- memberships.upsert(OrganizationUser(OrganizationUserId(UUID.randomUUID()), org.id, actor.id, Seq(role), None))
    client <- clients.insert(
                allGenerators[Client].normal.generate
                  .copy(organizationId = org.id, createdBy = actor.id, modifiedBy = actor.id, archivedAt = None, archivedBy = None)
              )
    source <-
      projects.insert(
        allGenerators[Project].normal.generate
          .copy(organizationId = org.id, clientId = client.id, createdBy = actor.id, modifiedBy = actor.id, archivedAt = None, archivedBy = None)
      )
    target <- projects.insert(source.copy(id = ProjectId(UUID.randomUUID())))
    unit <- units.insert(
              allGenerators[WorkUnit].normal.generate.copy(
                organizationId = org.id,
                userId = actor.id,
                projectId = source.id,
                taskId = Some(WorkUnitTaskId("SOURCE")),
                duration = WorkUnitDuration(Duration.ofHours(1)),
                state = WorkUnitState.Active,
                entryType = WorkUnitEntryType.Worklog,
                archivedAt = None,
                revision = 0,
                overrideState = WorkUnitOverrideState()
              )
            )
  } yield new Fixture(AuthContext(actor.id, actor.isSuperAdmin, Nil, Set.empty, Nil, Instant.now(), fresh = true), org, unit, target, member)

  private def ok(result: WorkUnitOverrideResult): WorkUnitDtoService.WorkUnitDto = result match {
    case Ok(value) => value
    case error     => throw new AssertionError(s"Expected success, got $error")
  }

  private def replaceTask(
    f: Fixture,
    revision: Long,
    task: String
  ): DBIO[WorkUnitOverrideResult] =
    sut.replaceAction(f.auth, f.org.slug, f.unit.id, ReplaceWorkUnitOverrideRequest(revision, None, WorkUnitTaskOverride("Set", Some(task))))

  private def undo(f: Fixture, result: WorkUnitOverrideResult): DBIO[WorkUnitOverrideResult] = {
    val value = ok(result)
    sut.undoAction(f.auth, f.org.slug, f.unit.id, UndoWorkUnitOverrideRequest(value.revision, value.`override`.undo.get.candidateEventId.get))
  }
}
