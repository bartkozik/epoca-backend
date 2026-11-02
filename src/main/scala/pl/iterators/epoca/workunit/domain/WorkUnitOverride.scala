package pl.iterators.epoca.workunit.domain

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitTaskId

import java.time.Instant

final case class WorkUnitTaskOverride(mode: String, value: Option[String] = None) {
  def validated: Either[String, (Boolean, Option[WorkUnitTaskId])] = (mode, value) match {
    case ("Inherit", None) => Right((false, None))
    case ("Clear", None)   => Right((true, None))
    case ("Set", Some(raw)) =>
      val trimmed = raw.trim
      Either.cond(trimmed.nonEmpty && trimmed.codePointCount(0, trimmed.length) <= 255, (true, Some(WorkUnitTaskId(trimmed))), "taskId")
    case _ => Left("taskId")
  }
}

object WorkUnitTaskOverride {
  implicit val decoder: Decoder[WorkUnitTaskOverride] = Decoder.instance { cursor =>
    for {
      mode  <- cursor.get[String]("mode")
      value <- cursor.get[Option[String]]("value")
      result <- if ((mode == "Inherit" || mode == "Clear") && cursor.keys.exists(_.exists(_ == "value")))
                  Left(DecodingFailure("Inherit and Clear must not contain value", cursor.history))
                else {
                  val task = WorkUnitTaskOverride(mode, value)
                  task.validated.left.map(_ => DecodingFailure("Invalid Task ID override", cursor.history)).map(_ => task)
                }
    } yield result
  }
  implicit val encoder: Encoder[WorkUnitTaskOverride] = Encoder.instance { task =>
    Json.obj((Seq("mode" -> Json.fromString(task.mode)) ++ task.value.map(value => "value" -> Json.fromString(value))): _*)
  }
}

final case class WorkUnitOverrideState(
  projectId: Option[ProjectId] = None,
  taskIdOverrideSet: Boolean = false,
  taskId: Option[WorkUnitTaskId] = None,
  version: Long = 0,
  hasHistory: Boolean = false,
  lastChangedBy: Option[UserId] = None,
  lastChangedAt: Option[Instant] = None) {
  def active: Boolean = projectId.nonEmpty || taskIdOverrideSet
  def desiredTask: WorkUnitTaskOverride =
    if (!taskIdOverrideSet) WorkUnitTaskOverride("Inherit")
    else taskId.fold(WorkUnitTaskOverride("Clear"))(value => WorkUnitTaskOverride("Set", Some(value)))

  def normalized(source: WorkUnit): WorkUnitOverrideState = {
    val inheritsTask = !taskIdOverrideSet || taskId == source.taskId
    copy(projectId = projectId.filterNot(_ == source.projectId), taskIdOverrideSet = !inheritsTask, taskId = if (inheritsTask) None else taskId)
  }

  def sameDesiredState(other: WorkUnitOverrideState): Boolean =
    projectId == other.projectId && taskIdOverrideSet == other.taskIdOverrideSet && taskId == other.taskId
}
