package pl.iterators.epoca.workunit.domain

import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.utils.json.JsonProtocol._

import java.util.UUID

object WorkUnitOverrideUndo {
  def candidateId(latest: Option[WorkUnitOverrideHistory], version: Long): Option[UUID] =
    latest.filter(event => event.version == version && event.provenance == "WorkUnitOverride").flatMap {
      case event if event.operation == "Replace" || event.operation == "Clear" => Some(event.id)
      case event if event.operation == "Undo"                                  => event.parentCorrectionId
      case _                                                                   => None
    }

  def restore(event: WorkUnitOverrideHistory): Either[String, WorkUnitOverrideState] =
    for {
      _      <- Either.cond(event.provenance == "WorkUnitOverride" && Set("Replace", "Clear").contains(event.operation), (), "targetEventId")
      before <- event.before.toRight("targetEventId")
      cursor = before.hcursor
      _         <- Either.cond(cursor.keys.exists(_.toSet.contains("projectId")), (), "targetEventId")
      project   <- cursor.get[Option[ProjectId]]("projectId").left.map(_ => "targetEventId")
      flag      <- cursor.get[Boolean]("taskIdOverrideSet").left.map(_ => "targetEventId")
      task      <- cursor.get[WorkUnitTaskOverride]("taskId").left.map(_ => "targetEventId")
      validated <- task.validated.left.map(_ => "targetEventId")
      _         <- Either.cond(flag == validated._1, (), "targetEventId")
    } yield WorkUnitOverrideState(project, validated._1, validated._2)
}
