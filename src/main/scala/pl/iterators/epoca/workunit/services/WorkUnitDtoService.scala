package pl.iterators.epoca.workunit.services

import cats.effect.unsafe.IORuntime
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.domain.ClientDomain.{ClientColor, ClientId, ClientName, ClientTexture}
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectDescription, ProjectId, ProjectName}
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain.{UserFullName, UserId}
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOrder}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService._
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

import scala.collection.immutable.Seq

class WorkUnitDtoService(
  clientRepository: ClientRepository,
  userRepository: UserRepository,
  projectRepository: ProjectRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO(ioRuntime)
    with LoggingSupport {
  def prepareOrder(workUnits: Seq[WorkUnitDto], orders: Seq[WorkUnitOrder]): Map[WorkUnitDate, Seq[WorkUnitId]] =
    workUnits
      .groupBy(unit => (unit.userId, unit.date))
      .map { case ((userId, date), groupedUnits) =>
        date -> prepareDayOrdering(groupedUnits, orders.find(order => order.date == date && order.userId == userId))
      }

  def prepareDtos(workUnits: Seq[WorkUnit]): DBIO[Seq[WorkUnitDto]] =
    (for {
      projects <- listProjects(workUnits)
      clients  <- listClients(projects)
      users    <- listUsers(workUnits)
      projectClientMap = getProjectClientMap(projects, clients)
      userFullNameMap  = getUserIdUFullNameMap(users)
    } yield prepareWorkUnitDtos(workUnits, projectClientMap, userFullNameMap)).run

  def prepareDto(workUnit: WorkUnit): DBIO[WorkUnitDto] =
    prepareDtos(Seq(workUnit)).map(_.head)

  private def prepareDayOrdering(workUnits: Seq[WorkUnitDto], order: Option[WorkUnitOrder]): Seq[WorkUnitId] = {
    val priorityWorkUnits: Seq[WorkUnitId] = order.map(_.workUnitIds).getOrElse(Nil).distinct
    val workUnitsMap                       = workUnits.map(unit => unit.id -> unit).toMap
    val orderedUnits                       = priorityWorkUnits.flatMap(workUnitsMap.get)
    val remainingUnits                     = workUnits.filterNot(unit => priorityWorkUnits.contains(unit.id)).sortBy(_.createdAt)
    (orderedUnits ++ remainingUnits).map(_.id)
  }

  private def listProjects(workUnits: Seq[WorkUnit]): StepDBIO[Seq[Project]] =
    projectRepository
      .list(ProjectFilters(ids = Some(workUnits.flatMap(unit => Seq(unit.projectId, unit.effectiveProjectId)).distinct), includeArchived = true))
      .seal

  private def listClients(projects: Seq[Project]): StepDBIO[Seq[Client]] =
    clientRepository.list(ClientFilters(ids = Some(projects.map(_.clientId).distinct), includeArchived = true)).seal

  private def listUsers(workUnits: Seq[WorkUnit]): StepDBIO[Seq[User]] = {
    val userIds = workUnits.flatMap(unit => Seq(unit.userId) ++ unit.overrideState.lastChangedBy).distinct
    userRepository.list(UserFilters(ids = Some(userIds), includeArchived = true)).seal
  }

  private def getProjectClientMap(projects: Seq[Project], clients: Seq[Client]): Map[ProjectId, WorkUnitProjectClientDto] = {
    val clientsMap = clients.map(client => client.id -> client).toMap
    projects
      .map(project => project -> clientsMap.get(project.clientId))
      .collect { case (project, Some(client)) =>
        project.id -> WorkUnitProjectClientDto.from(client, project)
      }
      .toMap
  }

  private def getUserIdUFullNameMap(users: Seq[User]): Map[UserId, UserFullName] =
    users.map(user => user.id -> user.fullName).toMap

  private def prepareWorkUnitDtos(
    workUnits: Seq[WorkUnit],
    projectClientMap: Map[ProjectId, WorkUnitProjectClientDto],
    userFullNameMap: Map[UserId, UserFullName]
  ): Seq[WorkUnitDto] =
    workUnits.map(WorkUnitDto.from(_, projectClientMap, userFullNameMap))

}

object WorkUnitDtoService {
  final case class WorkUnitProjectClientDto(
    clientId: ClientId,
    clientColor: ClientColor,
    clientTexture: ClientTexture,
    clientName: ClientName,
    projectId: ProjectId,
    projectName: ProjectName,
    projectDescription: Option[ProjectDescription])

  final case class WorkUnitDto(
    id: WorkUnitId,
    userId: UserId,
    userFullName: Option[UserFullName],
    date: WorkUnitDate,
    duration: WorkUnitDuration,
    entryType: WorkUnitEntryType,
    description: WorkUnitDescription,
    taskId: Option[WorkUnitTaskId],
    state: WorkUnitState,
    createdAt: WorkUnitCreatedAt,
    modifiedAt: WorkUnitModifiedAt,
    projectClient: Option[WorkUnitProjectClientDto],
    effectiveProjectClient: Option[WorkUnitProjectClientDto],
    effectiveTaskId: Option[WorkUnitTaskId],
    revision: Long,
    `override`: WorkUnitOverrideDto)

  object WorkUnitProjectClientDto {
    def from(client: Client, project: Project): WorkUnitProjectClientDto =
      WorkUnitProjectClientDto(
        clientId = client.id,
        clientColor = client.color,
        clientTexture = client.texture,
        clientName = client.name,
        projectId = project.id,
        projectName = project.name,
        projectDescription = project.description
      )
  }
  object WorkUnitDto {
    import pl.iterators.epoca.utils.json.JsonProtocol._

    implicit lazy val encoder: Encoder.AsObject[WorkUnitDto] =
      deriveEncoder[WorkUnitDto]

    def apply(
      id: WorkUnitId,
      userId: UserId,
      userFullName: Option[UserFullName],
      date: WorkUnitDate,
      duration: WorkUnitDuration,
      entryType: WorkUnitEntryType,
      description: WorkUnitDescription,
      taskId: Option[WorkUnitTaskId],
      state: WorkUnitState,
      createdAt: WorkUnitCreatedAt,
      modifiedAt: WorkUnitModifiedAt,
      projectClient: Option[WorkUnitProjectClientDto]
    ): WorkUnitDto =
      new WorkUnitDto(
        id,
        userId,
        userFullName,
        date,
        duration,
        entryType,
        description,
        taskId,
        state,
        createdAt,
        modifiedAt,
        projectClient,
        effectiveProjectClient = projectClient,
        effectiveTaskId = taskId,
        revision = 0,
        `override` = WorkUnitOverrideDto.empty
      )

    def from(
      workUnit: WorkUnit,
      projectClientMap: Map[ProjectId, WorkUnitProjectClientDto],
      userFullNameMap: Map[UserId, UserFullName]
    ): WorkUnitDto = workUnit
      .into[WorkUnitDto]
      .withFieldComputed(_.projectClient, workUnit => projectClientMap.get(workUnit.projectId))
      .withFieldComputed(_.userFullName, workUnit => userFullNameMap.get(workUnit.userId))
      .withFieldComputed(_.effectiveProjectClient, workUnit => projectClientMap.get(workUnit.effectiveProjectId))
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, workUnit => WorkUnitOverrideDto.from(workUnit, userFullNameMap))
      .transform
  }
}
